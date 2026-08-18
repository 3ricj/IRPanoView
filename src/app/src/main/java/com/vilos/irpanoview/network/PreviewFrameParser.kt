package com.vilos.irpanoview.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parses IRP8 preview payloads (inside IRPR length-framed TCP packets). */
object PreviewFrameParser {
    const val MAGIC = 0x38505249 // "IRP8"
    const val HEADER_BYTES = 28

    data class ParsedFrame(
        val width: Int,
        val height: Int,
        val sequence: Int,
        val timestampUs: Long,
        val minC: Float,
        val maxC: Float,
        /** Windowed u8 pixels, row-major, size = width * height. */
        val pixels: ByteArray,
    )

    fun parse(packet: ByteArray): ParsedFrame? {
        if (packet.size < HEADER_BYTES) {
            return null
        }
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != MAGIC) {
            return null
        }
        val width = buf.short.toInt() and 0xFFFF
        val height = buf.short.toInt() and 0xFFFF
        val sequence = buf.int
        val timestampUs = buf.long
        val minC = buf.float
        val maxC = buf.float
        val pixelsNeeded = width * height
        if (width == 0 || height == 0 || packet.size < HEADER_BYTES + pixelsNeeded) {
            return null
        }
        val pixels = packet.copyOfRange(HEADER_BYTES, HEADER_BYTES + pixelsNeeded)
        return ParsedFrame(
            width = width,
            height = height,
            sequence = sequence,
            timestampUs = timestampUs,
            minC = minC,
            maxC = maxC,
            pixels = pixels,
        )
    }
}
