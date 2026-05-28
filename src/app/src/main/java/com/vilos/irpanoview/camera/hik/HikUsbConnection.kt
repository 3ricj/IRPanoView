package com.vilos.irpanoview.camera.hik

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.vilos.irpanoview.util.AgentDebugLog
import com.vilos.irpanoview.util.PreviewSnapshotLogger
import com.vilos.irpanoview.util.UvcDebugLogger

/**
 * Android [UsbDeviceConnection] wrapper: interface claim, control plane, bulk IN.
 */
class HikUsbConnection(
    private val connection: UsbDeviceConnection,
    override val device: UsbDevice,
    override val windex: Int = HikUvcConstants.WINDEX_XU,
    private val busPath: String = device.deviceName ?: "unknown",
) : HikUsbLink {

    override var logContext: android.content.Context? = null

    override val androidConnection: UsbDeviceConnection get() = connection
    override val usesNativeLibusb: Boolean = false
    private val _claimedInterfaces = mutableListOf<Int>()
    override val claimedInterfaces: List<Int> get() = _claimedInterfaces.toList()
    override var bulkInEndpoint: UsbEndpoint? = null
        private set

    override fun claimForHik(): Int {
        val plan = discoverClaimPlan()
        for (ifaceNum in plan.interfaceNumbers) {
            val iface = device.getInterface(ifaceNum)
            if (!connection.claimInterface(iface, true)) {
                throw HikProtocolException("claimInterface($ifaceNum) failed")
            }
            _claimedInterfaces.add(ifaceNum)
        }
        bulkInEndpoint = plan.bulkIn
        // Alt setting is selected after UVC VS COMMIT in HikUvcStream (not here).
        return plan.bulkIn?.address ?: HikTherm.BULK_EP_DEFAULT
    }

    /** Select a specific alternate setting on [interfaceNumber] (VS probe uses alt 0). */
    override fun selectInterfaceAlt(interfaceNumber: Int, alternateSetting: Int): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == interfaceNumber && iface.alternateSetting == alternateSetting) {
                return connection.setInterface(iface)
            }
        }
        return false
    }

    /** Bounded [selectInterfaceAlt] — never block shutdown on wedged setInterface. */
    fun selectInterfaceAltBounded(interfaceNumber: Int, alternateSetting: Int, timeoutMs: Long): Boolean {
        return runOnThreadWithTimeout(
            "hik-android-alt-$busPath-$interfaceNumber-$alternateSetting",
            timeoutMs,
        ) {
            selectInterfaceAlt(interfaceNumber, alternateSetting)
        }
    }

    override fun controlWrite(bm: Int, breq: Int, wvalue: Int, windex: Int, data: ByteArray) {
        val rc = connection.controlTransfer(
            bm,
            breq,
            wvalue,
            windex,
            data,
            data.size,
            HikUvcConstants.CONTROL_TIMEOUT_MS,
        )
        if (rc < 0) {
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H1-H4",
                location = "HikUsbConnection.controlWrite:fail",
                message = "android controlTransfer rc=$rc",
                data = mapOf(
                    "bm" to bm,
                    "bReq" to breq,
                    "wVal" to wvalue,
                    "wValHex" to "0x${wvalue.toString(16)}",
                    "wIndex" to windex,
                    "claimed" to claimedInterfaces.toString(),
                    "bulkEp" to (bulkInEndpoint?.address?.toString(16) ?: "null"),
                    "busPath" to busPath,
                ),
            )
            // #endregion
            throw HikProtocolException(
                "controlWrite failed rc=$rc bm=${bm.toString(16)} bReq=$breq wVal=${wvalue.toString(16)}",
            )
        }
    }

    override fun controlRead(bm: Int, breq: Int, wvalue: Int, windex: Int, length: Int): ByteArray {
        val buf = ByteArray(length)
        val rc = connection.controlTransfer(
            bm,
            breq,
            wvalue,
            windex,
            buf,
            length,
            HikUvcConstants.CONTROL_TIMEOUT_MS,
        )
        if (rc < 0) {
            throw HikProtocolException(
                "controlRead failed rc=$rc bm=${bm.toString(16)} bReq=$breq wVal=${wvalue.toString(16)} len=$length",
            )
        }
        return if (rc == length) buf else buf.copyOf(rc.coerceAtLeast(0))
    }

    private val uvcReassembler = HikUvcBulkReassembler()
    private val bulkRequest = HikUsbBulkRequest(connection)
    private val lastChunkSizes = mutableListOf<Int>()
    override var lastFrameMeta: HikStreamProbe.FrameMeta? = null
    override var lastFrameBoundary: HikFrameBoundarySnapshot? = null

    override fun hasPartialPayload(): Boolean = uvcReassembler.stats.payloadBytesInFrame > 0

    override fun bulkDrainOneFrame(
        endpointAddress: Int,
        drainMs: Int,
        shouldAbort: () -> Boolean,
    ): ByteArray? {
        val endpoint = bulkInEndpoint?.takeIf { it.address == endpointAddress }
            ?: findEndpoint(endpointAddress)
            ?: throw HikProtocolException("bulk endpoint ${endpointAddress.toString(16)} not found")

        val chunk = ByteArray(16_384)
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
            val xferMs = if (shouldAbort()) {
                HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS
            } else {
                HikUvcConstants.BULK_DRAIN_TRANSFER_MS
            }
            val rc = bulkTransferIn(endpoint, chunk, xferMs, shouldAbort)
            if (shouldAbort()) {
                resetBulkCarry("abort")
                if (tracing) {
                    HikStartupBulkTrace.endDrainSlice(logContext, busPath, gotFrame = false, droppedPartial = false, dropReason = null)
                }
                return null
            }
            when {
                rc > 0 -> {
                    lastChunkSizes.add(rc)
                    ingestBulkChunk(chunk, rc)?.let {
                        if (tracing) {
                            HikStartupBulkTrace.endDrainSlice(logContext, busPath, gotFrame = true, droppedPartial = false, dropReason = null)
                        }
                        return it
                    }
                }
                hasPartialPayload() -> continue
                rc < 0 && !hasPartialPayload() && lastChunkSizes.isEmpty() ->
                    throw HikProtocolException("bulk read ep=${endpointAddress.toString(16)} rc=$rc")
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

    /** Discard stale UVC payload bytes after stream arm — sync only (do not touch UsbRequest). */
    fun primeBulkEndpoint(endpointAddress: Int) {
        flushBulkEndpointSync(
            endpointAddress,
            deadlineMs = 6L * HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS,
            emptyStreakLimit = 1,
        )
    }

    data class BulkFlushResult(
        val xfers: Int,
        val bytes: Int,
        val emptyStreak: Int,
        val elapsedMs: Long,
    )

    /**
     * Sync bulk discard (startup prime) — avoids priming [HikUsbBulkRequest] before pipeline arm.
     */
    fun flushBulkEndpointSync(
        endpointAddress: Int,
        deadlineMs: Long = HikUvcConstants.BULK_SHUTDOWN_FLUSH_MS,
        emptyStreakLimit: Int = HikUvcConstants.BULK_SHUTDOWN_FLUSH_EMPTY_STREAK,
        perXferMs: Int = HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS,
    ): BulkFlushResult {
        val endpoint = bulkInEndpoint?.takeIf { it.address == endpointAddress }
            ?: findEndpoint(endpointAddress)
            ?: return BulkFlushResult(0, 0, 0, 0L)
        val chunk = ByteArray(16_384)
        val startMs = System.currentTimeMillis()
        val deadline = startMs + deadlineMs
        var xfers = 0
        var bytes = 0
        var emptyStreak = 0
        while (System.currentTimeMillis() < deadline && emptyStreak < emptyStreakLimit) {
            val rc = connection.bulkTransfer(endpoint, chunk, 0, chunk.size, perXferMs)
            xfers++
            if (rc > 0) {
                bytes += rc
                emptyStreak = 0
            } else {
                emptyStreak++
            }
        }
        resetBulkCarry()
        return BulkFlushResult(
            xfers = xfers,
            bytes = bytes,
            emptyStreak = emptyStreak,
            elapsedMs = System.currentTimeMillis() - startMs,
        )
    }

    /**
     * Read and discard bulk IN while still on the streaming alt (pre-disarm flush).
     * Stops on [deadlineMs], [emptyStreakLimit] consecutive empty reads, or both.
     */
    override fun flushBulkEndpoint(
        endpointAddress: Int,
        deadlineMs: Long,
        emptyStreakLimit: Int,
        perXferMs: Int,
    ): BulkFlushResult {
        val endpoint = bulkInEndpoint?.takeIf { it.address == endpointAddress }
            ?: findEndpoint(endpointAddress)
            ?: return BulkFlushResult(0, 0, 0, 0L)
        val chunk = ByteArray(16_384)
        val startMs = System.currentTimeMillis()
        val deadline = startMs + deadlineMs
        var xfers = 0
        var bytes = 0
        var emptyStreak = 0
        while (System.currentTimeMillis() < deadline && emptyStreak < emptyStreakLimit) {
            val rc = bulkTransferIn(endpoint, chunk, perXferMs)
            xfers++
            if (rc > 0) {
                bytes += rc
                emptyStreak = 0
            } else {
                emptyStreak++
            }
        }
        resetBulkCarry()
        return BulkFlushResult(
            xfers = xfers,
            bytes = bytes,
            emptyStreak = emptyStreak,
            elapsedMs = System.currentTimeMillis() - startMs,
        )
    }

    /**
     * One bulk IN transfer under [HikBulkGate] — returns a completed super-frame or null.
     * Used when global bulk mutex serializes cameras (3+ streams).
     */
    override fun bulkPollSuperFrame(
        endpointAddress: Int,
        timeoutMs: Int,
        shouldAbort: () -> Boolean,
    ): ByteArray? {
        if (shouldAbort()) {
            resetBulkCarry()
            return null
        }
        val endpoint = bulkInEndpoint?.takeIf { it.address == endpointAddress }
            ?: findEndpoint(endpointAddress)
            ?: throw HikProtocolException("bulk endpoint ${endpointAddress.toString(16)} not found")

        val chunk = ByteArray(16_384)
        val xferMs = if (shouldAbort()) {
            HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS
        } else {
            timeoutMs
        }
        val rc = bulkTransferIn(endpoint, chunk, xferMs, shouldAbort)
        if (shouldAbort()) {
            resetBulkCarry()
            return null
        }
        return when {
            rc > 0 -> {
                lastChunkSizes.add(rc)
                ingestBulkChunk(chunk, rc)
            }
            hasPartialPayload() || lastChunkSizes.isNotEmpty() -> null
            rc < 0 -> throw HikProtocolException(
                "bulk read ep=${endpointAddress.toString(16)} rc=$rc",
            )
            else -> null
        }
    }

    /**
     * Blocking read for tests/tools. Streaming uses [bulkPollSuperFrame] from worker loops.
     */
    override fun bulkReadSuperFrame(
        endpointAddress: Int,
        timeoutMs: Int,
    ): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        var partialSinceMs = 0L
        while (System.currentTimeMillis() < deadline) {
            bulkPollSuperFrame(endpointAddress)?.let { return it }
            if (hasPartialPayload()) {
                if (partialSinceMs == 0L) partialSinceMs = System.currentTimeMillis()
                if (System.currentTimeMillis() - partialSinceMs > HikUvcConstants.PARTIAL_FRAME_TIMEOUT_MS) {
                    resetBulkCarry()
                    partialSinceMs = 0L
                }
            } else {
                partialSinceMs = 0L
            }
        }
        resetBulkCarry()
        throw HikProtocolException(
            "bulk super-frame timeout payload=${uvcReassembler.stats.payloadBytesInFrame}/${HikTherm.FRAME_BYTES} B " +
                "packets=${uvcReassembler.stats.packetsInFrame}",
        )
    }

    private val completeWireFrames = ArrayDeque<ByteArray>()

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

    override fun consumeRawWireDisplay(): ByteArray? = completeWireFrames.removeFirstOrNull()

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
            val head = hexHead(frame)
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
        if (HikStartupBulkTrace.isActive(busPath)) {
            HikStartupBulkTrace.onFrameComplete(logContext, busPath, stats.packetsInFrame, frame.size)
        }
        val cx = HikTherm.GRID_WIDTH / 2
        val cy = HikTherm.GRID_HEIGHT / 2
        val meta = HikStreamProbe.FrameMeta(
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
        lastFrameMeta = meta
        lastChunkSizes.clear()
        loggedNonStandardSize = false
        return frame
    }

    private fun hexHead(frame: ByteArray): String = buildString {
        val n = minOf(16, frame.size)
        for (i in 0 until n) {
            append(String.format("%02x", frame[i]))
        }
    }

    data class BulkCancelResult(
        val cancelCalled: Int,
        val drained: Int,
        val elapsedMs: Long,
        val inFlightAfter: Boolean,
    )

    /** Cancel in-flight async bulk IN (StopChannel analog). Call before worker join on Exit. */
    override fun cancelBulkIn(
        drainAfterCancel: Boolean,
        cancelWaitMs: Int,
    ): BulkCancelResult {
        logContext?.let { ctx ->
            com.vilos.irpanoview.util.UvcDebugLogger.log(ctx, busPath, "BULK_CANCEL_REQUESTED")
        }
        val result = if (drainAfterCancel) {
            bulkRequest.cancelAndDrain()
        } else {
            bulkRequest.cancelOnly()
        }
        return BulkCancelResult(
            cancelCalled = result.cancelCalled,
            drained = result.drained,
            elapsedMs = result.elapsedMs,
            inFlightAfter = result.inFlightAfter,
        )
    }

    private fun bulkTransferIn(
        endpoint: UsbEndpoint,
        chunk: ByteArray,
        timeoutMs: Int,
        shouldAbort: () -> Boolean = { false },
    ): Int {
        if (shouldAbort()) return 0
        val perCall = if (shouldAbort()) {
            HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS
        } else {
            timeoutMs.coerceIn(20, HikUvcConstants.BULK_TRANSFER_CAP_MS)
        }
        val stopFlag = shouldAbort()
        HikBulkInstrumentation.onBulkInEnter(logContext, busPath, endpoint.address, perCall, stopFlag)
        val startMs = System.currentTimeMillis()
        val rc = HikBulkGate.runBulkExclusive {
            if (shouldAbort()) return@runBulkExclusive 0
            connection.bulkTransfer(endpoint, chunk, 0, chunk.size, perCall)
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        HikBulkInstrumentation.onBulkInComplete(
            logContext,
            busPath,
            endpoint.address,
            perCall,
            rc,
            elapsedMs,
            stopFlag || shouldAbort(),
        )
        if (HikStartupBulkTrace.isActive(busPath)) {
            val stats = uvcReassembler.stats
            val uvcBm = if (rc >= 2) chunk[1].toInt() and 0xFF else null
            HikStartupBulkTrace.onXfer(
                logContext,
                busPath,
                rc,
                perCall,
                elapsedMs,
                stats.payloadBytesInFrame,
                stats.packetsInFrame,
                uvcBm,
            )
        }
        return rc
    }

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

    override fun resetBulkRequest() {
        bulkRequest.close()
    }

    override fun startNativeBulkIfNeeded(endpointAddress: Int) = Unit

    override fun stopNativeChannel(vsInterface: Int): Long? = null

    /** Pre-queue async bulk IN URB after stream arm (StopChannel cancel target). */
    fun primeBulkPipeline(endpointAddress: Int) {
        val endpoint = bulkInEndpoint?.takeIf { it.address == endpointAddress }
            ?: findEndpoint(endpointAddress)
            ?: return
        bulkRequest.primePipeline(endpoint, logContext, busPath)
    }

    fun bulkReadExact(endpointAddress: Int, length: Int, timeoutMs: Int = HikUvcConstants.BULK_TIMEOUT_MS): ByteArray {
        val endpoint = bulkInEndpoint?.takeIf { it.address == endpointAddress }
            ?: findEndpoint(endpointAddress)
            ?: throw HikProtocolException("bulk endpoint ${endpointAddress.toString(16)} not found")
        val buf = ByteArray(length)
        var offset = 0
        val chunk = 16_384
        val deadline = System.currentTimeMillis() + timeoutMs
        while (offset < length) {
            val now = System.currentTimeMillis()
            if (now >= deadline) break
            val want = minOf(chunk, length - offset)
            val perCallTimeout = (deadline - now).toInt().coerceIn(100, timeoutMs)
            val rc = connection.bulkTransfer(endpoint, buf, offset, want, perCallTimeout)
            when {
                rc > 0 -> offset += rc
                offset == 0 && rc < 0 -> throw HikProtocolException(
                    "bulk read ep=${endpointAddress.toString(16)} rc=$rc",
                )
                else -> Thread.sleep(5)
            }
        }
        // Tail: super-frame last few bytes often arrive in a separate micro-transfer.
        var tailAttempts = 0
        while (offset < length && tailAttempts < 100) {
            val want = length - offset
            val rc = connection.bulkTransfer(endpoint, buf, offset, want, 250)
            if (rc > 0) {
                offset += rc
            } else {
                tailAttempts++
                Thread.sleep(2)
            }
        }
        if (offset != length) {
            if (offset >= length - 4 && offset > length / 2) {
                // Android bulk sometimes omits the last few bytes of a super-frame; tail is padding.
                return buf
            }
            throw HikProtocolException(
                "bulk read ep=${endpointAddress.toString(16)} incomplete $offset/$length B",
            )
        }
        return buf
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

    /** Select highest alternate setting on the bulk-IN interface (UVC stream prep). */
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
            if (hasBulkIn && (best == null || iface.alternateSetting > best.alternateSetting)) {
                best = iface
            }
        }
        if (best == null) return false
        return connection.setInterface(best)
    }

    /** Release claimed interfaces only — keeps [UsbDeviceConnection] open (StopChannel / soft pause). */
    override fun releaseClaimedInterfaces(closeBulk: Boolean): List<Int> {
        if (closeBulk) {
            bulkRequest.close()
        }
        val ifaces = _claimedInterfaces.toList()
        for (ifaceNum in ifaces.asReversed()) {
            runCatching {
                connection.releaseInterface(device.getInterface(ifaceNum))
            }
        }
        _claimedInterfaces.clear()
        bulkInEndpoint = null
        resetBulkCarry()
        return ifaces
    }

    /** Release Android USB handle only — no UVC alt-0 (doc 06 wire-confirmed stop path). */
    override fun closeHandle() {
        closeHandleBounded(CLOSE_HANDLE_TIMEOUT_MS)
    }

    /**
     * Timed close — skip [releaseInterface] (wedged on VS alt; leaves zombie kernel threads).
     * [UsbDeviceConnection.close] only, same as libusb path androidRelease=skipped.
     */
    fun closeHandleBounded(timeoutMs: Long = CLOSE_HANDLE_TIMEOUT_MS): Boolean {
        val bulkCloseStartMs = System.currentTimeMillis()
        bulkRequest.close()
        val bulkCloseMs = System.currentTimeMillis() - bulkCloseStartMs
        val ifaces = _claimedInterfaces.toList()
        val startMs = System.currentTimeMillis()
        _claimedInterfaces.clear()
        bulkInEndpoint = null
        resetBulkCarry()
        val closed = runOnThreadWithTimeout("hik-android-close-$busPath", timeoutMs) {
            connection.close()
        }
        val totalMs = System.currentTimeMillis() - startMs
        val abandoned = !closed
        logContext?.let { ctx ->
            com.vilos.irpanoview.util.UvcDebugLogger.log(
                ctx,
                busPath,
                "Hik USB closeHandle: ifaces=$ifaces androidRelease=skipped timeout=${timeoutMs}ms " +
                    "abandoned=$abandoned elapsed=${totalMs}ms",
            )
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H17",
            location = "HikUsbConnection.closeHandleBounded",
            message = if (abandoned) "close abandoned" else "close ok",
            data = mapOf(
                "busPath" to busPath,
                "ifaces" to ifaces.toString(),
                "timeoutMs" to timeoutMs,
                "elapsedMs" to totalMs,
                "bulkCloseMs" to bulkCloseMs,
                "abandoned" to abandoned,
                "androidRelease" to "skipped",
            ),
            runId = "post-fix",
        )
        AgentDebugLog.log(
            hypothesisId = "H26",
            location = "HikUsbConnection.closeHandleBounded:bulkClose",
            message = "bulkRequest.close timing",
            data = mapOf(
                "busPath" to busPath,
                "bulkCloseMs" to bulkCloseMs,
                "closeMs" to totalMs,
            ),
            runId = "post-fix",
        )
        // #endregion
        return closed
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
                com.vilos.irpanoview.util.UvcDebugLogger.log(
                    ctx,
                    busPath,
                    "Hik USB op abandoned thread=$name after ${timeoutMs}ms",
                )
            }
            return false
        }
        if (err != null) {
            logContext?.let { ctx ->
                com.vilos.irpanoview.util.UvcDebugLogger.log(
                    ctx,
                    busPath,
                    "Hik USB op failed thread=$name: ${err!!.message}",
                )
            }
        }
        return ok
    }

    override fun close() {
        closeHandle()
    }

    data class ClaimPlan(
        val interfaceNumbers: List<Int>,
        val altSettings: List<UsbInterface>,
        val bulkIn: UsbEndpoint?,
    )

    fun discoverClaimPlan(): ClaimPlan {
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
                    if (bulkIn == null || ep.address == HikTherm.BULK_EP_DEFAULT) {
                        bulkIn = ep
                    }
                }
            }
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO || hasBulkIn) {
                ifaceNums.add(iface.id)
                if (iface.alternateSetting > 0) {
                    alts.add(iface)
                }
            }
        }
        if (ifaceNums.isEmpty()) {
            ifaceNums.addAll(listOf(0, 1).filter { it < device.interfaceCount })
        }
        return ClaimPlan(ifaceNums.toList(), alts, bulkIn)
    }

    companion object {
        const val OPEN_TIMEOUT_MS = 12_000L
        const val CLOSE_HANDLE_TIMEOUT_MS = 400L

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            timeoutMs: Long = OPEN_TIMEOUT_MS,
            stopCheck: () -> Boolean = { false },
        ): HikUsbConnection {
            val busPath = device.deviceName ?: "unknown"
            HikUsbStackRecovery.checkCanOpen(busPath)
            HikUsbStackRecovery.waitForInflightClear(busPath, stopCheck)
            if (stopCheck()) {
                throw HikProtocolException("open cancelled")
            }
            HikUsbStackRecovery.checkCanOpen(busPath)

            var result: HikUsbConnection? = null
            var error: Exception? = null
            val opener = Thread(
                {
                    try {
                        result = openBlocking(manager, device)
                    } catch (e: Exception) {
                        error = e
                    } finally {
                        HikUsbStackRecovery.clearInflight(busPath, Thread.currentThread())
                    }
                },
                "hik-open-$busPath",
            )
            opener.isDaemon = true
            HikUsbStackRecovery.registerInflight(busPath, opener)
            opener.start()
            opener.join(timeoutMs)
            if (opener.isAlive) {
                HikUsbStackRecovery.recordOpenTimeout(busPath)
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H34",
                    location = "HikUsbConnection.open:timeout",
                    message = "openDevice timeout — keep inflight until thread returns",
                    data = mapOf(
                        "busPath" to busPath,
                        "timeoutMs" to timeoutMs,
                        "settleRemainMs" to HikUsbStackRecovery.postStopSettleRemainMs(),
                        "anyInflight" to HikUsbStackRecovery.hasAnyInflight(),
                    ),
                    runId = "post-fix",
                )
                // #endregion
                throw HikProtocolException("UsbManager.openDevice timeout after ${timeoutMs}ms")
            }
            error?.let { throw it }
            return result ?: throw HikProtocolException("UsbManager.openDevice returned null")
        }

        private fun openBlocking(manager: UsbManager, device: UsbDevice): HikUsbConnection {
            val conn = manager.openDevice(device)
                ?: throw HikProtocolException("UsbManager.openDevice returned null")
            return HikUsbConnection(conn, device)
        }
    }
}
