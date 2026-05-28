package com.vilos.irpanoview.camera.hik

import java.util.Locale

/**
 * Raw UVC payload boundary observationd at device frame edges (EOF / FID flip).
 * Shown on-screen so wire start/end can be trimmed without guessing sizes.
 */
data class HikFrameBoundarySnapshot(
    val assembledBytes: Int,
    val startHex: String,
    val endHex: String,
    val packetCount: Int,
    val trigger: String,
    val lastBm: Int,
    val lastHeaderLen: Int,
    val lastXferHex: String,
    val lastXferRc: Int,
    /** Bytes trimmed from assembled payload start (`0` or `2` for `0x7377` leader). */
    val wireLeaderTrimBytes: Int,
    val emitted: Boolean,
    val outputBytes: Int,
    val framingMode: String,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun displayLines(): String = buildString {
        append("◀ START ")
        append(startHex.chunkedHex())
        append('\n')
        append("▶ END   ")
        append(endHex.chunkedHex())
        append('\n')
        append(assembledBytes)
        append("B ")
        append(trigger)
        append(" pkts=")
        append(packetCount)
        append(" bm=0x")
        append(lastBm.toString(16))
        append(" hdr=")
        append(lastHeaderLen)
        append(' ')
        append(if (emitted) "OK→${outputBytes}B" else "REJECT")
        if (wireLeaderTrimBytes > 0) append(" leaderTrim=$wireLeaderTrimBytes")
        if (lastXferRc > 0) {
            append('\n')
            append("usb rc=")
            append(lastXferRc)
            append(' ')
            append(lastXferHex.chunkedHex())
        }
    }

    companion object {
        private const val EDGE_BYTES = 16

        fun fromAssembled(
            assembled: ByteArray,
            packetCount: Int,
            trigger: String,
            lastBm: Int,
            lastHeaderLen: Int,
            lastXfer: ByteArray,
            lastXferRc: Int,
            emitted: Boolean,
            outputBytes: Int,
            framingMode: HikBulkFramingMode,
        ): HikFrameBoundarySnapshot {
            val start = hexEdge(assembled, fromStart = true)
            val end = hexEdge(assembled, fromStart = false)
            return HikFrameBoundarySnapshot(
                assembledBytes = assembled.size,
                startHex = start,
                endHex = end,
                packetCount = packetCount,
                trigger = trigger,
                lastBm = lastBm,
                lastHeaderLen = lastHeaderLen,
                lastXferHex = hexBytes(lastXfer, 0, minOf(16, lastXferRc.coerceAtLeast(0))),
                lastXferRc = lastXferRc,
                wireLeaderTrimBytes = wireLeaderTrimBytes(assembled),
                emitted = emitted,
                outputBytes = outputBytes,
                framingMode = framingMode.logTag,
            )
        }

        private fun wireLeaderTrimBytes(assembled: ByteArray): Int =
            if (assembled.size > HikUvcBulkReassembler.WIRE_LEADER_BYTES + 2 &&
                (assembled[0].toInt() and 0xFF) == 0x73 &&
                (assembled[1].toInt() and 0xFF) == 0x77
            ) {
                HikUvcBulkReassembler.WIRE_LEADER_BYTES
            } else {
                0
            }

        private fun hexEdge(buf: ByteArray, fromStart: Boolean): String {
            if (buf.isEmpty()) return "—"
            val n = minOf(EDGE_BYTES, buf.size)
            return if (fromStart) {
                hexBytes(buf, 0, n)
            } else {
                hexBytes(buf, buf.size - n, n)
            }
        }

        private fun hexBytes(buf: ByteArray, off: Int, len: Int): String = buildString {
            val end = minOf(off + len, buf.size)
            var i = off
            while (i < end) {
                append(String.format(Locale.US, "%02x", buf[i]))
                i++
            }
        }

        private fun String.chunkedHex(): String {
            if (length <= 32) return this
            return chunked(2).joinToString(" ")
        }
    }
}
