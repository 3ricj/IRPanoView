package com.vilos.irpanoview.camera.hik

/**
 * Bulk IN → UVC payload bytes → 200704 B wire frame (doc 07).
 *
 * libuvc-style FID/EOF assembly; payload is emitted verbatim (no leader trim).
 */
internal class HikUvcBulkReassembler {

    data class Stats(
        val packetsInFrame: Int = 0,
        val payloadBytesInFrame: Int = 0,
        val frameWireBytes: Int = 0,
        val lastHeaderLen: Int = 0,
        val lastBmHeaderInfo: Int = 0,
        val bytesSkippedForSync: Int = 0,
        val framingNote: String? = null,
    )

    data class FramingEvent(
        val kind: String,
        val bytesDropped: Int,
        val headHex: String,
    )

    private val payloadRing = ByteArray(RING_CAPACITY)
    private var ringHead = 0
    private var ringTail = 0

    private var uvcPacketsInFlight = 0
    private var payloadBytesInFlight = 0
    private var frameWireBytes = 0
    private var lastHeaderLen = 0
    private var lastBmHeaderInfo = 0
    private var lastCompletedStats: Stats? = null
    private var lastFramingEvent: FramingEvent? = null
    private var lastBoundarySnapshot: HikFrameBoundarySnapshot? = null
    private var lastRawWirePayload: ByteArray? = null
    private var lastSizeGateRejectedPayload: ByteArray? = null
    private var hadCompletedFrame = false

    /** Last raw USB bulk IN bytes (for boundary xfer line). */
    private val lastXferHead = ByteArray(32)
    private var lastXferRc = 0

    /** libuvc strmh->fid — last seen FID bit (0 or 1). */
    private var uvcFid: Int = 0
    /** On stream arm/reset, ignore payload until first EOF to avoid mid-frame startup fragments. */
    private var awaitingBoundaryEof: Boolean = true
    /** After sync EOF, wait for FID toggle to ensure frame-start packet alignment. */
    private var awaitingStartFidToggle: Boolean = false

    private val pendingBuf = ByteArray(16_384)
    private var pendingLen = 0

    val stats: Stats
        get() = Stats(
            packetsInFrame = uvcPacketsInFlight,
            payloadBytesInFrame = payloadBytesInFlight,
            frameWireBytes = frameWireBytes,
            lastHeaderLen = lastHeaderLen,
            lastBmHeaderInfo = lastBmHeaderInfo,
            bytesSkippedForSync = 0,
            framingNote = lastFramingEvent?.kind,
        )

    fun reset() {
        ringHead = 0
        ringTail = 0
        uvcPacketsInFlight = 0
        payloadBytesInFlight = 0
        frameWireBytes = 0
        lastHeaderLen = 0
        lastBmHeaderInfo = 0
        lastCompletedStats = null
        lastFramingEvent = null
        lastSizeGateRejectedPayload = null
        hadCompletedFrame = false
        pendingLen = 0
        uvcFid = 0
        awaitingBoundaryEof = true
        awaitingStartFidToggle = false
    }

    fun consumeLastCompletedStats(): Stats? {
        val snap = lastCompletedStats
        lastCompletedStats = null
        return snap
    }

    fun consumeLastFramingEvent(): FramingEvent? {
        val snap = lastFramingEvent
        lastFramingEvent = null
        return snap
    }

    fun consumeLastBoundarySnapshot(): HikFrameBoundarySnapshot? {
        val snap = lastBoundarySnapshot
        lastBoundarySnapshot = null
        return snap
    }

    @Volatile
    var retainRawWirePayload: Boolean = false

    @Volatile
    var retainFooterSlices: Boolean = false

    @Volatile
    var retainJumboHeaderSlices: Boolean = false

    private var lastFooter2: ByteArray? = null
    private var lastJumboHeader: ByteArray? = null

    /** Complete UVC payload between EOF/FID boundaries — set only when a whole frame is assembled. */
    fun consumeLastRawWirePayload(): ByteArray? {
        val payload = lastRawWirePayload
        lastRawWirePayload = null
        return payload
    }

    /** Full assembled payload rejected by the exact-size gate. */
    fun consumeLastSizeGateRejectedPayload(): ByteArray? {
        val payload = lastSizeGateRejectedPayload
        lastSizeGateRejectedPayload = null
        return payload
    }

    /** Footer2 slice from the last assembled wire payload (2048 B). */
    fun consumeLastFooter2(): ByteArray? {
        val f2 = lastFooter2
        lastFooter2 = null
        return f2
    }

    /** Jumbo header slice from the last accepted jumbo frame (typically 0x1220 B). */
    fun consumeLastJumboHeader(): ByteArray? {
        val header = lastJumboHeader
        lastJumboHeader = null
        return header
    }

    fun noteIncomingTransfer(transfer: ByteArray, length: Int) {
        lastXferRc = length
        if (length > 0) {
            val n = minOf(length, lastXferHead.size)
            transfer.copyInto(lastXferHead, 0, 0, n)
        }
    }

    /** Feed one raw bulk IN transfer. Returns a completed UVC wire frame when one is available. */
    fun feed(transfer: ByteArray, length: Int): ByteArray? {
        if (length <= 0) {
            recordFraming("uvc_empty_transfer", 0, "")
            return null
        }
        noteIncomingTransfer(transfer, length)
        val input = mergePending(transfer, length)
        var offset = 0
        var completed: ByteArray? = null
        while (offset < input.size) {
            val result = feedOneUvcPacket(input, offset, input.size - offset)
            if (result == null) {
                stashPending(input, offset, input.size)
                break
            }
            offset += result.consumed
            frameWireBytes += result.consumed
            result.frame?.let { frame ->
                if (completed != null) {
                    recordFraming("uvc_multi_frame_in_transfer", 0, headHexBytes(frame, minOf(16, frame.size)))
                }
                completed = frame
            }
        }
        return completed
    }

    /** Reset UVC FID state at stream arm (libuvc sets strmh->fid = 0 on stream start). */
    fun releaseAwaitingFrameBoundary() {
        uvcFid = 0
        awaitingBoundaryEof = true
        awaitingStartFidToggle = false
        clearUvcAccumulation()
    }

    /** Test hook — feed a full swrf-over frame without prior sync EOF. */
    internal fun skipBoundarySyncForTest() {
        awaitingBoundaryEof = false
        awaitingStartFidToggle = false
        clearUvcAccumulation()
    }

    private fun mergePending(transfer: ByteArray, length: Int): ByteArray {
        if (pendingLen == 0) {
            return if (length == transfer.size) transfer else transfer.copyOf(length)
        }
        val merged = ByteArray(pendingLen + length)
        pendingBuf.copyInto(merged, 0, 0, pendingLen)
        transfer.copyInto(merged, pendingLen, 0, length)
        pendingLen = 0
        return merged
    }

    private fun stashPending(input: ByteArray, from: Int, to: Int) {
        val remain = to - from
        if (remain <= 0) return
        require(remain <= pendingBuf.size) { "pending overflow $remain" }
        input.copyInto(pendingBuf, 0, from, to)
        pendingLen = remain
    }

    private data class UvcFeedResult(
        val consumed: Int,
        val frame: ByteArray?,
    )

    private fun feedOneUvcPacket(buf: ByteArray, start: Int, avail: Int): UvcFeedResult? {
        if (avail < 2) return null

        val headerLen = buf[start].toInt() and 0xFF
        if (headerLen < 2) {
            recordFraming("uvc_bad_header_len", avail, headHexBytesRange(buf, start, minOf(16, avail)))
            clearUvcAccumulation()
            return UvcFeedResult(consumed = avail, frame = null)
        }
        if (headerLen > avail) return null

        val bmHeaderInfo = buf[start + 1].toInt() and 0xFF
        lastHeaderLen = headerLen
        lastBmHeaderInfo = bmHeaderInfo

        return processUvcEofPacket(buf, start, avail, headerLen, bmHeaderInfo)
    }

    /**
     * libuvc _uvc_process_payload — FID/EOF assembly with Hik wire-leader trim on emit.
     */
    private fun processUvcEofPacket(
        buf: ByteArray,
        start: Int,
        avail: Int,
        headerLen: Int,
        headerInfo: Int,
    ): UvcFeedResult {
        if (headerInfo and BM_ERR != 0) {
            recordFraming("uvc_err", ringSize(), headHexRing(0, minOf(16, ringSize())))
            clearUvcAccumulation()
            val consumed = if (headerLen > 0) headerLen.coerceAtMost(avail) else avail
            return UvcFeedResult(consumed = consumed, frame = null)
        }

        val eof = headerInfo and BM_EOF != 0
        val newFid = headerInfo and BM_FID
        if (awaitingBoundaryEof) {
            if (eof) {
                uvcFid = newFid
                awaitingBoundaryEof = false
                awaitingStartFidToggle = true
                recordFraming("uvc_sync_eof", 0, headHexBytesRange(buf, start, minOf(16, avail)))
            }
            return UvcFeedResult(consumed = avail, frame = null)
        }

        if (awaitingStartFidToggle) {
            if (newFid == uvcFid) {
                recordFraming("uvc_sync_wait_fid_toggle", 0, headHexBytesRange(buf, start, minOf(16, avail)))
                return UvcFeedResult(consumed = avail, frame = null)
            }
            awaitingStartFidToggle = false
            recordFraming("uvc_sync_fid_toggle", 0, headHexBytesRange(buf, start, minOf(16, avail)))
        }

        var dataLen = if (headerLen > 0) (avail - headerLen).coerceAtLeast(0) else avail
        var completed: ByteArray? = null

        if (headerLen >= 2) {
            uvcFid = newFid
        }

        if (dataLen > 0) {
            val payloadStart = start + headerLen
            val room = MAX_UVC_ASSEMBLY_BYTES - ringSize()
            if (room <= 0) {
                recordFraming("uvc_overflow", ringSize(), headHexRing(0, minOf(16, ringSize())))
                clearUvcAccumulation()
                dataLen = 0
            } else if (dataLen > room) {
                val trunc = dataLen - room
                recordFraming("uvc_payload_truncated", trunc, headHexBytesRange(buf, payloadStart, minOf(16, dataLen)))
                dataLen = room
            }
            if (dataLen > 0) {
                appendPayloadRing(buf, payloadStart, dataLen)
                uvcPacketsInFlight++
                payloadBytesInFlight = ringSize()
            }
        }

        if (eof && ringSize() == 0) {
            recordFraming("uvc_empty_eof", 0, "")
        }
        if (completed == null && eof) {
            completed = tryEmitUvcFrame("eof")
        }

        val consumed = if (headerLen > 0) headerLen + dataLen else avail
        return UvcFeedResult(consumed = consumed.coerceAtMost(avail), frame = completed)
    }

    private fun tryEmitUvcFrame(trigger: String): ByteArray? {
        val size = ringSize()
        if (size == 0) return null

        val assembled = ByteArray(size)
        copyRing(0, assembled, 0, size)
        val pktsForFrame = uvcPacketsInFlight
        val normalized = normalizeAssembledFrame(assembled)
        val frame = normalized.frame

        recordBoundarySnapshot(
            assembled = assembled,
            packetCount = pktsForFrame,
            trigger = trigger,
            emitted = frame != null,
            outputBytes = frame?.size ?: 0,
        )
        if (frame != null) {
            retainWirePayloadSlices(frame, normalized.jumboHeader)
        }
        clearUvcAccumulation()

        if (frame == null) {
            if (normalized.rejectKind == "uvc_size_over" && lastSizeGateRejectedPayload == null) {
                lastSizeGateRejectedPayload = assembled.copyOf()
            }
            recordFraming(
                normalized.rejectKind ?: "uvc_reject",
                kotlin.math.abs(size - HikTherm.FRAME_BYTES),
                headHexBytes(assembled, minOf(16, assembled.size)),
            )
            if (normalized.rejectKind == "uvc_swrf_eof_reject") {
                awaitingStartFidToggle = true
            }
            return null
        }

        if (normalized.kind == "uvc_jumbo") {
            recordFraming(
                "uvc_jumbo_accept",
                normalized.jumboHeader?.size ?: 0,
                headHexBytes(assembled, minOf(16, assembled.size)),
            )
        }

        hadCompletedFrame = true
        lastCompletedStats = Stats(
            packetsInFrame = pktsForFrame,
            payloadBytesInFrame = frame.size,
            frameWireBytes = frameWireBytes,
            lastHeaderLen = lastHeaderLen,
            lastBmHeaderInfo = lastBmHeaderInfo,
            bytesSkippedForSync = 0,
            framingNote = lastFramingEvent?.kind,
        )
        return frame
    }

    private fun recordBoundarySnapshot(
        assembled: ByteArray,
        packetCount: Int,
        trigger: String,
        emitted: Boolean,
        outputBytes: Int,
    ) {
        lastBoundarySnapshot = HikFrameBoundarySnapshot.fromAssembled(
            assembled = assembled,
            packetCount = packetCount,
            trigger = trigger,
            lastBm = lastBmHeaderInfo,
            lastHeaderLen = lastHeaderLen,
            lastXfer = lastXferHead,
            lastXferRc = lastXferRc,
            emitted = emitted,
            outputBytes = outputBytes,
            framingMode = HikBulkFramingMode.active,
        )
    }

    private fun clearUvcAccumulation() {
        ringHead = 0
        ringTail = 0
        uvcPacketsInFlight = 0
        payloadBytesInFlight = 0
    }

    /** EOF/FID payload → assembled UVC wire payload (optional 2 B `0x7377` leader trim). */
    internal fun extractUvcWirePayload(assembled: ByteArray): ByteArray? {
        if (assembled.isEmpty()) return null
        if (assembled.size == WIRE_LEADER_BYTES + HikTherm.FRAME_BYTES &&
            assembled[0] == 0x73.toByte() &&
            assembled[1] == 0x77.toByte()
        ) {
            return assembled.copyOfRange(WIRE_LEADER_BYTES, assembled.size)
        }
        return assembled.copyOf()
    }

    private data class NormalizedFrameResult(
        val kind: String,
        val frame: ByteArray?,
        val jumboHeader: ByteArray? = null,
        val rejectKind: String? = null,
    )

    private fun normalizeAssembledFrame(assembled: ByteArray): NormalizedFrameResult {
        val expected = HikTherm.FRAME_BYTES
        if (assembled.size == expected) {
            return NormalizedFrameResult(kind = "uvc_regular", frame = assembled.copyOf())
        }

        extractUvcWirePayload(assembled)?.let { frame ->
            if (frame.size == expected) {
                return NormalizedFrameResult(kind = "uvc_regular", frame = frame)
            }
        }

        normalizeJumboFrame(assembled)?.let { jumbo ->
            return NormalizedFrameResult(
                kind = "uvc_jumbo",
                frame = jumbo.first,
                jumboHeader = jumbo.second,
            )
        }

        val rejectKind = when {
            assembled.size < expected -> "uvc_size_under"
            else -> "uvc_size_over"
        }
        return NormalizedFrameResult(kind = "uvc_reject", frame = null, rejectKind = rejectKind)
    }

    /**
     * Jumbo framing carries two 98,304-byte planes (temp + yuv) after a variable-length header.
     * The header is preserved for logging and output is normalized to canonical 200,704-byte wire.
     */
    private fun normalizeJumboFrame(assembled: ByteArray): Pair<ByteArray, ByteArray>? {
        if (assembled.size !in HikUvcWireLayout.JUMBO_TOTAL_MIN_BYTES..HikUvcWireLayout.JUMBO_TOTAL_MAX_BYTES) {
            return null
        }
        val swrfOffset = findSwrfMagicOffset(assembled)
        if (swrfOffset < 0) return null
        val planeBytes = HikUvcWireLayout.JUMBO_PLANE_BYTES
        val visibleStart = assembled.size - planeBytes
        val tempStart = visibleStart - planeBytes
        if (tempStart <= 0 || visibleStart <= tempStart) return null

        val canonical = ByteArray(HikTherm.FRAME_BYTES)
        assembled.copyInto(
            canonical,
            destinationOffset = HikUvcWireLayout.RADIO_OFFSET,
            startIndex = tempStart,
            endIndex = visibleStart,
        )
        assembled.copyInto(
            canonical,
            destinationOffset = HikUvcWireLayout.VISIBLE_OFFSET,
            startIndex = visibleStart,
            endIndex = assembled.size,
        )
        val header = assembled.copyOfRange(0, tempStart)
        return canonical to header
    }

    private fun findSwrfMagicOffset(buf: ByteArray): Int {
        val maxScan = minOf(HikUvcWireLayout.JUMBO_SWRF_SCAN_BYTES, buf.size - 4)
        for (i in 0..maxScan) {
            if (isSwrfWireHeader(buf, i)) return i
        }
        return -1
    }

    private fun isSwrfWireHeader(buf: ByteArray, offset: Int): Boolean {
        if (offset + 3 >= buf.size) return false
        return buf[offset] == 0x73.toByte() &&
            buf[offset + 1] == 0x77.toByte() &&
            buf[offset + 2] == 0x82.toByte() &&
            buf[offset + 3] == 0x70.toByte()
    }

    private fun recordFraming(kind: String, bytesDropped: Int, headHex: String) {
        lastFramingEvent = FramingEvent(kind, bytesDropped, headHex)
    }

    private fun headHexRing(offset: Int, length: Int): String = buildString {
        val n = minOf(length, ringSize() - offset)
        for (i in 0 until n) {
            append(String.format("%02x", ringByte(offset + i)))
        }
    }

    private fun headHexBytes(buf: ByteArray, length: Int): String = buildString {
        val n = minOf(length, buf.size)
        for (i in 0 until n) {
            append(String.format("%02x", buf[i].toInt() and 0xFF))
        }
    }

    private fun headHexBytesRange(buf: ByteArray, offset: Int, length: Int): String = buildString {
        if (offset >= buf.size || length <= 0) return@buildString
        val n = minOf(length, buf.size - offset)
        for (i in 0 until n) {
            append(String.format("%02x", buf[offset + i].toInt() and 0xFF))
        }
    }

    private fun ringSize(): Int = ringTail - ringHead

    private fun ringByte(index: Int): Int = payloadRing[ringHead + index].toInt() and 0xFF

    private fun appendPayloadRing(src: ByteArray, offset: Int, length: Int) {
        ensureRingCapacity(length)
        src.copyInto(payloadRing, ringTail, offset, offset + length)
        ringTail += length
    }

    private fun ensureRingCapacity(appendLen: Int) {
        if (ringTail + appendLen <= payloadRing.size) return
        compactRing()
        if (ringTail + appendLen > payloadRing.size) {
            recordFraming("uvc_ring_reset", appendLen, headHexRing(0, minOf(16, ringSize())))
            reset()
        }
    }

    private fun compactRing() {
        if (ringHead == 0) return
        val size = ringSize()
        if (size > 0) {
            payloadRing.copyInto(payloadRing, 0, ringHead, ringTail)
        }
        ringHead = 0
        ringTail = size
    }

    private fun copyRing(srcOffset: Int, dest: ByteArray, destOffset: Int, length: Int) {
        payloadRing.copyInto(dest, destOffset, ringHead + srcOffset, ringHead + srcOffset + length)
    }

    private fun retainWirePayloadSlices(frame: ByteArray, jumboHeader: ByteArray?) {
        lastRawWirePayload = if (retainRawWirePayload) frame.copyOf() else null
        if (!retainFooterSlices) {
            lastFooter2 = null
        } else {
            val end = HikUvcWireLayout.FOOTER2_OFFSET + HikUvcWireLayout.FOOTER2_BYTES
            if (frame.size < end) {
                lastFooter2 = null
            } else {
                lastFooter2 = frame.copyOfRange(
                    HikUvcWireLayout.FOOTER2_OFFSET,
                    HikUvcWireLayout.FOOTER2_OFFSET + HikUvcWireLayout.FOOTER2_BYTES,
                )
            }
        }
        lastJumboHeader = if (retainJumboHeaderSlices && jumboHeader != null) jumboHeader.copyOf() else null
    }

    companion object {
        private const val RING_CAPACITY = 512 * 1024

        /** Max UVC payload bytes per frame (wire + optional leader). */
        internal val MAX_UVC_ASSEMBLY_BYTES: Int =
            (HikUvcWireLayout.PAYLOAD_BYTES * 3) / 2

        const val BM_FID = 0x01
        const val BM_EOF = 0x02
        const val BM_PTS = 0x04
        const val BM_SCR = 0x08
        const val BM_ERR = 0x40

        const val WIRE_LEADER_BYTES = 2

        /** Bound-relaunch mis-sync block before radiometric composite (201248 − 200704). */
        const val SWRF_PREFIX_BYTES = 544

        /** Assembled EOF size when firmware prepends [SWRF_PREFIX_BYTES] swrf header. */
        const val SWRF_OVER_FRAME_BYTES = HikTherm.FRAME_BYTES + SWRF_PREFIX_BYTES

        /**
         * Split a golden super-frame into UVC bulk packets (for tests).
         * [bulkTransferBytes] is total USB bytes per transfer including the UVC header.
         */
        fun packetizeFrame(
            frame: ByteArray,
            bulkTransferBytes: Int = 5034,
            fid: Boolean = true,
            deviceHighBit: Boolean = false,
        ): List<ByteArray> {
            require(frame.size == HikTherm.FRAME_BYTES) {
                "expected ${HikTherm.FRAME_BYTES} B frame, got ${frame.size}"
            }
            require(bulkTransferBytes > 2)
            val headerLen = 2
            val maxPayload = bulkTransferBytes - headerLen
            val packets = ArrayList<ByteArray>()
            var offset = 0
            while (offset < frame.size) {
                val payloadLen = minOf(maxPayload, frame.size - offset)
                val isLast = offset + payloadLen >= frame.size
                val packet = ByteArray(headerLen + payloadLen)
                packet[0] = headerLen.toByte()
                var bm = if (fid) BM_FID else 0
                if (isLast) bm = bm or BM_EOF
                if (deviceHighBit) bm = bm or 0x80
                packet[1] = bm.toByte()
                frame.copyInto(packet, headerLen, offset, offset + payloadLen)
                packets.add(packet)
                offset += payloadLen
            }
            return packets
        }
    }
}
