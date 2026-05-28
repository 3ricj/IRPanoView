package com.vilos.irpanoview.camera.hik

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.vilos.irpanoview.util.PreviewSnapshotLogger
import com.vilos.irpanoview.util.UvcDebugLogger

/**
 * Hik USB via libusb on Android fd ([libusb_wrap_sys_device]).
 */
class LibusbHikConnection private constructor(
    private val connection: UsbDeviceConnection,
    override val device: UsbDevice,
    private val nativeHandle: Long,
    override val windex: Int = HikUvcConstants.WINDEX_XU,
    private val busPath: String = device.deviceName ?: "unknown",
) : HikUsbLink {

    override val androidConnection: UsbDeviceConnection = connection
    override var logContext: Context? = null
    override val usesNativeLibusb: Boolean = true

    private val claimed = mutableListOf<Int>()
    override val claimedInterfaces: List<Int> get() = claimed.toList()
    override var bulkInEndpoint: UsbEndpoint? = null
        private set

    private val uvcReassembler = HikUvcBulkReassembler()
    private val lastChunkSizes = mutableListOf<Int>()
    override var lastFrameMeta: HikStreamProbe.FrameMeta? = null
    override var lastFrameBoundary: HikFrameBoundarySnapshot? = null

    private var bulkStarted = false
    private var vsInterfaceId: Int = 1
    private var stopChannelDone = false
    private var nativeClosed = false

    override fun claimForHik(): Int {
        val plan = discoverClaimPlan()
        for (ifaceNum in plan.interfaceNumbers) {
            val iface = device.getInterface(ifaceNum)
            if (!connection.claimInterface(iface, true)) {
                throw HikProtocolException("claimInterface($ifaceNum) failed (Android)")
            }
            claimed.add(ifaceNum)
            val libusbRc = HikNativeUsb.nativeClaimInterface(nativeHandle, ifaceNum)
            if (libusbRc != 0 && libusbRc != LIBUSB_ERROR_BUSY) {
                logContext?.let { ctx ->
                    UvcDebugLogger.log(
                        ctx,
                        busPath,
                        "LIBUSB claim mirror iface=$ifaceNum rc=$libusbRc (Android ok)",
                    )
                }
            }
        }
        bulkInEndpoint = plan.bulkIn
        vsInterfaceId = plan.interfaceNumbers.lastOrNull { it > 0 } ?: 1
        logContext?.let { ctx ->
            UvcDebugLogger.log(ctx, busPath, "LIBUSB hybrid claim ifaces=$claimed bulkEp=${plan.bulkIn?.address?.toString(16)}")
        }
        return plan.bulkIn?.address ?: HikTherm.BULK_EP_DEFAULT
    }

    override fun selectInterfaceAlt(interfaceNumber: Int, alternateSetting: Int): Boolean {
        // Streaming alt: libusb on wrapped fd (Android setInterface often fails for VS alt>0).
        if (alternateSetting > 0) {
            val rc = HikNativeUsb.nativeSetInterfaceAlt(nativeHandle, interfaceNumber, alternateSetting)
            if (rc == 0) {
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    if (iface.id == interfaceNumber && iface.alternateSetting == alternateSetting) {
                        bulkInEndpoint = findBulkIn(iface)
                        break
                    }
                }
                logContext?.let { ctx ->
                    UvcDebugLogger.log(ctx, busPath, "LIBUSB setAlt if=$interfaceNumber alt=$alternateSetting")
                }
                return true
            }
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == interfaceNumber && iface.alternateSetting == alternateSetting) {
                if (connection.setInterface(iface)) return true
            }
        }
        val rc = HikNativeUsb.nativeSetInterfaceAlt(nativeHandle, interfaceNumber, alternateSetting)
        return rc == 0
    }

    override fun controlWrite(bm: Int, breq: Int, wvalue: Int, windex: Int, data: ByteArray) {
        val rc = HikNativeUsb.nativeControlWrite(nativeHandle, bm, breq, wvalue, windex, data)
        if (rc < 0) {
            throw HikProtocolException(
                "libusb controlWrite failed rc=$rc bm=${bm.toString(16)} bReq=$breq wVal=${wvalue.toString(16)}",
            )
        }
    }

    override fun controlRead(bm: Int, breq: Int, wvalue: Int, windex: Int, length: Int): ByteArray {
        val buf = HikNativeUsb.nativeControlRead(nativeHandle, bm, breq, wvalue, windex, length)
            ?: throw HikProtocolException(
                "libusb controlRead failed bm=${bm.toString(16)} bReq=$breq wVal=${wvalue.toString(16)} len=$length",
            )
        return if (buf.size == length) buf else buf.copyOf(buf.size.coerceAtMost(length))
    }

    override fun startNativeBulkIfNeeded(endpointAddress: Int) {
        if (bulkStarted) return
        val ep = bulkInEndpoint?.takeIf { it.address == endpointAddress }
            ?: findEndpoint(endpointAddress)
            ?: return
        val rc = HikNativeUsb.nativeStartBulkIn(nativeHandle, ep.address, ep.maxPacketSize)
        bulkStarted = rc == 0
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB bulk start ep=${ep.address.toString(16)} maxPkt=${ep.maxPacketSize} rc=$rc",
            )
        }
    }

    override fun bulkPollSuperFrame(
        endpointAddress: Int,
        timeoutMs: Int,
        shouldAbort: () -> Boolean,
    ): ByteArray? {
        if (shouldAbort()) {
            resetBulkCarry()
            return null
        }
        startNativeBulkIfNeeded(endpointAddress)
        val xferMs = if (shouldAbort()) {
            HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS
        } else {
            timeoutMs
        }
        val stopFlag = shouldAbort()
        HikBulkInstrumentation.onBulkInEnter(logContext, busPath, endpointAddress, xferMs, stopFlag)
        val pollStart = System.currentTimeMillis()
        val chunk = HikNativeUsb.nativePollBulkChunk(nativeHandle, 16_384, xferMs)
        val pollElapsed = System.currentTimeMillis() - pollStart
        val rc = chunk?.size ?: 0
        HikBulkInstrumentation.onBulkInComplete(
            logContext, busPath, endpointAddress, xferMs, rc, pollElapsed, stopFlag,
        )
        if (HikStartupBulkTrace.isActive(busPath)) {
            val stats = uvcReassembler.stats
            val uvcBm = if (chunk != null && chunk.size >= 2) chunk[1].toInt() and 0xFF else null
            HikStartupBulkTrace.onXfer(
                logContext,
                busPath,
                if (chunk == null || chunk.isEmpty()) -1 else rc,
                xferMs,
                pollElapsed,
                stats.payloadBytesInFrame,
                stats.packetsInFrame,
                uvcBm,
            )
        }
        if (shouldAbort()) {
            resetBulkCarry()
            return null
        }
        if (chunk == null || chunk.isEmpty()) {
            return if (hasPartialPayload() || lastChunkSizes.isNotEmpty()) null else null
        }
        lastChunkSizes.add(chunk.size)
        return ingestBulkChunk(chunk, chunk.size)
    }

    override fun bulkDrainOneFrame(
        endpointAddress: Int,
        drainMs: Int,
        shouldAbort: () -> Boolean,
    ): ByteArray? {
        val tracing = HikStartupBulkTrace.isActive(busPath)
        if (tracing) {
            HikStartupBulkTrace.beginDrainSlice(logContext, busPath, drainMs)
        }
        val deadline = System.currentTimeMillis() + drainMs
        while (System.currentTimeMillis() < deadline) {
            if (shouldAbort()) {
                resetBulkCarry("abort")
                if (tracing) {
                    HikStartupBulkTrace.endDrainSlice(logContext, busPath, gotFrame = false, droppedPartial = false, dropReason = null)
                }
                return null
            }
            bulkPollSuperFrame(endpointAddress, HikUvcConstants.BULK_DRAIN_TRANSFER_MS, shouldAbort)?.let {
                if (tracing) {
                    HikStartupBulkTrace.endDrainSlice(logContext, busPath, gotFrame = true, droppedPartial = false, dropReason = null)
                }
                return it
            }
        }
        val hadPartial = hasPartialPayload()
        if (hadPartial && !HikBulkFramingMode.active.preservePartialAcrossDrainSlices) {
            resetBulkCarry("drain_expired")
        }
        if (tracing) {
            val dropped = hadPartial && !HikBulkFramingMode.active.preservePartialAcrossDrainSlices
            HikStartupBulkTrace.endDrainSlice(
                logContext,
                busPath,
                gotFrame = false,
                droppedPartial = dropped,
                dropReason = if (dropped) "drain_expired" else null,
            )
        }
        return null
    }

    override fun hasPartialPayload(): Boolean = uvcReassembler.stats.payloadBytesInFrame > 0

    override fun resetBulkCarry(reason: String?) {
        val stats = uvcReassembler.stats
        if (reason != null && stats.payloadBytesInFrame > 0) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(
                    ctx,
                    busPath,
                    "reset_bulk_carry reason=$reason payload=${stats.payloadBytesInFrame} pkts=${stats.packetsInFrame}",
                )
            }
        }
        if (HikStartupBulkTrace.isActive(busPath) && reason != null && reason != "drain_expired") {
            if (stats.payloadBytesInFrame > 0) {
                HikStartupBulkTrace.onFraming(
                    logContext,
                    busPath,
                    kind = "reset_carry:$reason",
                    bytesDropped = stats.payloadBytesInFrame,
                    headHex = "pkts=${stats.packetsInFrame}",
                )
            }
        }
        uvcReassembler.reset()
        lastChunkSizes.clear()
        lastFrameMeta = null
        lastFrameBoundary = null
        completeWireFrames.clear()
    }

    override fun primeUvcFrameAssembly() {
        uvcReassembler.releaseAwaitingFrameBoundary()
    }

    override fun cancelBulkIn(
        drainAfterCancel: Boolean,
        cancelWaitMs: Int,
    ): HikUsbConnection.BulkCancelResult {
        logContext?.let { ctx -> UvcDebugLogger.log(ctx, busPath, "BULK_CANCEL_REQUESTED drain=$drainAfterCancel") }
        if (nativeClosed) {
            return HikUsbConnection.BulkCancelResult(
                cancelCalled = 0,
                drained = 0,
                elapsedMs = 0,
                inFlightAfter = false,
            )
        }
        val detail = HikNativeUsb.nativeCancelBulkInDetail(nativeHandle, cancelWaitMs)
        val cancelCalled = detail.getOrElse(0) { 0 }
        val elapsed = detail.getOrElse(1) { 0 }.toLong()
        var inFlight = detail.getOrElse(2) { 0 } == 1
        bulkStarted = false
        var drained = 0
        if (drainAfterCancel && inFlight) {
            val deadline = System.currentTimeMillis() + BULK_DRAIN_AFTER_CANCEL_MS
            while (System.currentTimeMillis() < deadline) {
                val chunk = HikNativeUsb.nativePollBulkChunk(nativeHandle, 16_384, 20)
                if (chunk == null || chunk.isEmpty()) break
                drained++
            }
            inFlight = false
        }
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "BULK_CANCEL complete cancel=$cancelCalled drained=$drained elapsed=${elapsed}ms inFlight=$inFlight",
            )
        }
        return HikUsbConnection.BulkCancelResult(
            cancelCalled = cancelCalled,
            drained = drained,
            elapsedMs = elapsed,
            inFlightAfter = inFlight,
        )
    }

    override fun resetBulkRequest() = Unit

    override fun stopNativeChannel(vsInterface: Int): Long {
        if (nativeClosed) return 0L
        val elapsed = HikNativeUsb.nativeStopChannelSoft(nativeHandle, vsInterface)
        bulkStarted = false
        resetBulkCarry()
        stopChannelDone = true
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB_STOP soft elapsed=${elapsed}ms vsIface=$vsInterface claimed=$claimed",
            )
        }
        return elapsed
    }

    override fun stopNativeChannelReference(vsInterface: Int, claimedIfaces: IntArray): Long {
        if (nativeClosed) return 0L
        val elapsed = HikNativeUsb.nativeStopChannelReference(
            nativeHandle,
            vsInterface,
            claimedIfaces,
        )
        bulkStarted = false
        resetBulkCarry()
        stopChannelDone = true
        claimed.removeAll(claimedIfaces.toSet())
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB_STOP ref elapsed=${elapsed}ms vsIface=$vsInterface ifaces=${claimedIfaces.contentToString()}",
            )
        }
        return elapsed
    }

    /** Full-exit stop fallback — cancel with 3 s wait, no alt-0. */
    fun stopNativeChannelFull(vsInterface: Int): Long {
        if (nativeClosed) return 0L
        val elapsed = HikNativeUsb.nativeStopChannelFull(nativeHandle, vsInterface)
        bulkStarted = false
        resetBulkCarry()
        stopChannelDone = true
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB_STOP full elapsed=${elapsed}ms vsIface=$vsInterface claimed=$claimed",
            )
        }
        return elapsed
    }

    override fun releaseClaimedInterfaces(closeBulk: Boolean): List<Int> {
        if (closeBulk) {
            cancelBulkIn(
                drainAfterCancel = true,
                cancelWaitMs = HikShutdownExperiment.CANCEL_WAIT_FULL_MS,
            )
        }
        val ifaces = claimed.toList()
        if (!nativeClosed && ifaces.isNotEmpty()) {
            val results = ifaces.asReversed().joinToString(",") { ifaceNum ->
                val rc = HikNativeUsb.nativeReleaseInterface(nativeHandle, ifaceNum)
                "$ifaceNum:$rc"
            }
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, busPath, "LIBUSB release ifaces=$results")
            }
        }
        claimed.clear()
        bulkInEndpoint = null
        resetBulkCarry()
        return ifaces
    }

    /** Detach close — cancel only, no alt-0 on likely-dead fd. */
    fun closeHandleDetached() {
        val ifaces = claimed.toList()
        cancelBulkIn(
            drainAfterCancel = false,
            cancelWaitMs = HikShutdownExperiment.CANCEL_WAIT_SOFT_MS,
        )
        if (!nativeClosed) {
            runCatching { HikNativeUsb.nativeClose(nativeHandle) }
            nativeClosed = true
        }
        claimed.clear()
        bulkInEndpoint = null
        resetBulkCarry()
        runCatching { connection.close() }
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB closeHandleDetached ifaces=$ifaces androidRelease=skipped",
            )
        }
    }

    override fun closeHandle() {
        val ifaces = claimed.toList()

        cancelBulkIn(
            drainAfterCancel = false,
            cancelWaitMs = HikShutdownExperiment.CANCEL_WAIT_FULL_MS,
        )

        if (!nativeClosed) {
            runCatching {
                HikNativeUsb.nativeCloseHandle(
                    nativeHandle,
                    vsInterfaceId,
                    ifaces.toIntArray(),
                )
            }
            nativeClosed = true
            stopChannelDone = true
        }

        claimed.clear()
        bulkInEndpoint = null
        resetBulkCarry()
        runCatching { connection.close() }
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB closeHandle ifaces=$ifaces nativeClosed=$nativeClosed alt0=native " +
                    "libusbRelease=native androidRelease=skipped",
            )
        }
    }

    /**
     * Per-iface timed release — never block shutdown thread on one kernel call.
     * Always returns; caller must still [UsbDeviceConnection.close].
     */
    private fun releaseAndroidInterfacesBounded(ifaces: List<Int>): Int {
        if (ifaces.isEmpty()) return 0
        val start = System.currentTimeMillis()
        var released = 0
        for (ifaceNum in ifaces.asReversed()) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= CLOSE_RELEASE_TOTAL_MS) {
                logContext?.let { ctx ->
                    UvcDebugLogger.log(
                        ctx,
                        busPath,
                        "LIBUSB release budget exhausted ${elapsed}ms ifaces=$ifaces released=$released",
                    )
                }
                break
            }
            val budget = (CLOSE_RELEASE_TOTAL_MS - elapsed).coerceAtMost(PER_OP_TIMEOUT_MS)
            val ok = runOnThreadWithTimeout("hik-libusb-rel-$busPath-$ifaceNum", budget) {
                connection.releaseInterface(device.getInterface(ifaceNum))
            }
            if (ok) released++
        }
        val totalMs = System.currentTimeMillis() - start
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB release bounded ifaces=$ifaces released=$released/${ifaces.size} elapsed=${totalMs}ms",
            )
        }
        return released
    }

    private fun runOnThreadWithTimeout(name: String, timeoutMs: Long, block: () -> Unit): Boolean {
        var ok = false
        var err: Throwable? = null
        val worker = Thread({
            try {
                block()
                ok = true
            } catch (t: Throwable) {
                err = t
            }
        }, name)
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, busPath, "LIBUSB op abandoned thread=$name after ${timeoutMs}ms")
            }
            return false
        }
        if (err != null) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, busPath, "LIBUSB op failed thread=$name: ${err!!.message}")
            }
        }
        return ok
    }

    override fun close() = closeHandle()

    override fun flushBulkEndpoint(
        endpointAddress: Int,
        deadlineMs: Long,
        emptyStreakLimit: Int,
        perXferMs: Int,
    ): HikUsbConnection.BulkFlushResult {
        val startMs = System.currentTimeMillis()
        if (nativeClosed) {
            return HikUsbConnection.BulkFlushResult(0, 0, 0, 0)
        }
        var xfers = 0
        var bytes = 0
        var emptyStreak = 0
        val deadline = startMs + deadlineMs
        while (System.currentTimeMillis() < deadline && emptyStreak < emptyStreakLimit) {
            val chunk = HikNativeUsb.nativePollBulkChunk(nativeHandle, 16_384, perXferMs)
            xfers++
            if (chunk != null && chunk.isNotEmpty()) {
                bytes += chunk.size
                emptyStreak = 0
            } else {
                emptyStreak++
            }
        }
        resetBulkCarry()
        return HikUsbConnection.BulkFlushResult(
            xfers = xfers,
            bytes = bytes,
            emptyStreak = emptyStreak,
            elapsedMs = System.currentTimeMillis() - startMs,
        )
    }

    override fun selectStreamingAltSetting(): Boolean {
        var best: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var hasBulkIn = false
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) {
                    hasBulkIn = true
                }
            }
            if (hasBulkIn && (best == null || iface.alternateSetting > best!!.alternateSetting)) {
                best = iface
            }
        }
        if (best == null) return false
        val ok = selectInterfaceAlt(best.id, best.alternateSetting)
        if (ok) {
            bulkInEndpoint = findBulkIn(best)
        }
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "LIBUSB streaming alt if=${best.id} alt=${best.alternateSetting} ok=$ok ep=${bulkInEndpoint?.address?.toString(16)}",
            )
        }
        return ok
    }

    private val completeWireFrames = ArrayDeque<ByteArray>()

    override fun consumeRawWireDisplay(): ByteArray? = completeWireFrames.removeFirstOrNull()

    private fun ingestBulkChunk(chunk: ByteArray, rc: Int): ByteArray? {
        uvcReassembler.retainRawWirePayload =
            PreviewSnapshotLogger.shouldRetainUvcWirePayload(
                busPath,
                HikPreviewSettings.RAW_WIRE_DISPLAY,
            )
        uvcReassembler.retainFooterSlices =
            PreviewSnapshotLogger.shouldRetainFooterSlices(busPath)
        uvcReassembler.retainJumboHeaderSlices =
            PreviewSnapshotLogger.shouldRetainJumboHeaderSlices(busPath)
        val frame = uvcReassembler.feed(chunk, rc)
        logFramingIfNeeded()
        uvcReassembler.consumeLastBoundarySnapshot()?.let { snap ->
            lastFrameBoundary = snap
            if (!snap.emitted) {
                logContext?.let { ctx ->
                    UvcDebugLogger.log(
                        ctx,
                        busPath,
                        "uvc_boundary_reject trigger=${snap.trigger} assembled=${snap.assembledBytes} " +
                            "pkts=${snap.packetCount} bm=0x${snap.lastBm.toString(16)} hdr=${snap.lastHeaderLen}",
                    )
                }
            }
        }
        uvcReassembler.consumeLastSizeGateRejectedPayload()?.let { payload ->
            logContext?.let { ctx ->
                PreviewSnapshotLogger.onSizeGateRejectedWireFrame(
                    context = ctx,
                    busPath = busPath,
                    payload = payload,
                    expectedBytes = HikTherm.FRAME_BYTES,
                )
            }
        }
        uvcReassembler.consumeLastRawWirePayload()?.let { payload ->
            if (HikPreviewSettings.RAW_WIRE_DISPLAY) {
                completeWireFrames.clear()
                completeWireFrames.addLast(payload)
            } else {
                logContext?.let { ctx ->
                    when {
                        PreviewSnapshotLogger.needsWireObservation(busPath) ->
                            PreviewSnapshotLogger.onUvcWireFrame(ctx, busPath, payload)
                        PreviewSnapshotLogger.needsWireObservation10s(busPath) ->
                            PreviewSnapshotLogger.onUvcWireFrame10s(ctx, busPath, payload)
                    }
                }
            }
        }
        uvcReassembler.consumeLastFooter2()?.let { footer2 ->
            logContext?.let { ctx ->
                PreviewSnapshotLogger.onUvcFooter2(ctx, busPath, footer2)
            }
        }
        uvcReassembler.consumeLastJumboHeader()?.let { header ->
            logContext?.let { ctx ->
                PreviewSnapshotLogger.onUvcJumboHeader(ctx, busPath, header)
            }
        }
        return frame?.let { finalizeUvcFrame(it) }
    }

    private fun logFramingIfNeeded() {
        val event = uvcReassembler.consumeLastFramingEvent() ?: return
        android.util.Log.w(
            "HikUvcBulk",
            "framing ${event.kind} dropped=${event.bytesDropped} head=${event.headHex}",
        )
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                busPath,
                "framing kind=${event.kind} dropped=${event.bytesDropped} head=${event.headHex}",
            )
        }
        if (HikStartupBulkTrace.isActive(busPath)) {
            HikStartupBulkTrace.onFraming(logContext, busPath, event.kind, event.bytesDropped, event.headHex)
        }
    }

    private var loggedNonStandardSize = false

    private fun finalizeUvcFrame(frame: ByteArray): ByteArray? {
        if (frame.size != HikTherm.FRAME_BYTES) {
            val head = buildString {
                val n = minOf(16, frame.size)
                for (i in 0 until n) append(String.format("%02x", frame[i]))
            }
            if (!loggedNonStandardSize) {
                loggedNonStandardSize = true
                android.util.Log.w(
                    "HikUvcBulk",
                    "frame non-standard size=${frame.size} expected=${HikTherm.FRAME_BYTES} head=$head",
                )
            }
            if (HikStartupBulkTrace.isActive(busPath)) {
                HikStartupBulkTrace.onFraming(
                    logContext,
                    busPath,
                    kind = "non_standard_frame_size",
                    bytesDropped = frame.size,
                    headHex = head,
                )
            }
        }
        val stats = uvcReassembler.consumeLastCompletedStats() ?: uvcReassembler.stats
        val cx = HikTherm.GRID_WIDTH / 2
        val cy = HikTherm.GRID_HEIGHT / 2
        lastFrameMeta = HikStreamProbe.FrameMeta(
            frameIndex = -1,
            ringBytesBefore = stats.frameWireBytes,
            alignOffset = stats.bytesSkippedForSync,
            alignScore = 0.0,
            centerCelsius = runCatching {
                HikTherm.celsiusAtPixel(frame, cx, cy)
            }.getOrDefault(Double.NaN),
            chunkSizes = lastChunkSizes.toList(),
            reassembly = HikBulkFramingMode.active.logTag,
            uvcPackets = stats.packetsInFrame,
        )
        lastChunkSizes.clear()
        loggedNonStandardSize = false
        return frame
    }

    override fun bulkReadSuperFrame(endpointAddress: Int, timeoutMs: Int): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        var partialSinceMs = 0L
        while (System.currentTimeMillis() < deadline) {
            bulkPollSuperFrame(endpointAddress, HikUvcConstants.BULK_TRANSFER_CAP_MS)?.let { return it }
            if (hasPartialPayload()) {
                if (partialSinceMs == 0L) partialSinceMs = System.currentTimeMillis()
                if (System.currentTimeMillis() - partialSinceMs > timeoutMs / 2) {
                    resetBulkCarry()
                    partialSinceMs = 0L
                }
            }
        }
        throw HikProtocolException("bulkReadSuperFrame timeout after ${timeoutMs}ms")
    }

    private fun findEndpoint(address: Int): UsbEndpoint? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.address == address) return ep
            }
        }
        return null
    }

    private fun findBulkIn(iface: UsbInterface): UsbEndpoint? {
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_IN
            ) {
                return ep
            }
        }
        return null
    }

    private fun discoverClaimPlan(): HikUsbConnection.ClaimPlan {
        val ifaceNums = linkedSetOf<Int>()
        var bulkIn: UsbEndpoint? = null
        val alts = mutableListOf<UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var hasBulkIn = false
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) {
                    hasBulkIn = true
                    if (bulkIn == null || ep.address == HikTherm.BULK_EP_DEFAULT) bulkIn = ep
                }
            }
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO || hasBulkIn) {
                ifaceNums.add(iface.id)
                if (iface.alternateSetting > 0) alts.add(iface)
            }
        }
        if (ifaceNums.isEmpty()) {
            ifaceNums.addAll(listOf(0, 1).filter { it < device.interfaceCount })
        }
        return HikUsbConnection.ClaimPlan(ifaceNums.toList(), alts, bulkIn)
    }

    fun streamingInterfaceId(): Int = vsInterfaceId

    companion object {
        /** libusb error code: interface already claimed (mirror claim after Android ok). */
        private const val LIBUSB_ERROR_BUSY = -6

        /** Max wait per setInterface / releaseInterface call. */
        private const val PER_OP_TIMEOUT_MS = 800L
        /** Total budget for all release calls on Exit. */
        private const val CLOSE_RELEASE_TOTAL_MS = 2_000L
        private const val BULK_DRAIN_AFTER_CANCEL_MS = 500L

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            timeoutMs: Long = HikUsbConnection.OPEN_TIMEOUT_MS,
            stopCheck: () -> Boolean = { false },
        ): LibusbHikConnection {
            val busPath = device.deviceName ?: "unknown"
            HikUsbStackRecovery.checkCanOpen(busPath)
            HikUsbStackRecovery.waitForInflightClear(busPath, stopCheck)
            if (stopCheck()) throw HikProtocolException("open cancelled")
            HikUsbStackRecovery.checkCanOpen(busPath)

            var result: LibusbHikConnection? = null
            var error: Exception? = null
            val opener = Thread({
                try {
                    result = openBlocking(manager, device)
                } catch (e: Exception) {
                    error = e
                } finally {
                    HikUsbStackRecovery.clearInflight(busPath, Thread.currentThread())
                }
            }, "hik-libusb-open-$busPath")
            opener.isDaemon = true
            HikUsbStackRecovery.registerInflight(busPath, opener)
            opener.start()
            opener.join(timeoutMs)
            if (opener.isAlive) {
                HikUsbStackRecovery.recordOpenTimeout(busPath)
                throw HikProtocolException("UsbManager.openDevice timeout after ${timeoutMs}ms")
            }
            error?.let { throw it }
            return result ?: throw HikProtocolException("libusb open returned null")
        }

        private fun openBlocking(manager: UsbManager, device: UsbDevice): LibusbHikConnection {
            val conn = manager.openDevice(device)
                ?: throw HikProtocolException("UsbManager.openDevice returned null")
            val fd = conn.fileDescriptor
            if (fd <= 0) throw HikProtocolException("invalid fileDescriptor=$fd")
            val handle = HikNativeUsb.nativeOpen(fd)
            if (handle == 0L) {
                conn.close()
                throw HikProtocolException("nativeOpen failed fd=$fd")
            }
            return LibusbHikConnection(conn, device, handle)
        }
    }
}
