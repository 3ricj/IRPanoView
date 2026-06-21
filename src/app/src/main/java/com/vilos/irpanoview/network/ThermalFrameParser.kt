package com.vilos.irpanoview.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ThermalFrameParser {
    const val MAGIC = 0x56505249 // "IRPV"
    const val HEADER_BYTES = 22
    const val FLAG_PER_CAMERA_HEALTH = 0x01

    const val DEFAULT_PANO_WIDTH = 1024
    const val DEFAULT_PANO_HEIGHT = 192

    /** @deprecated use [ParsedFrame.width] from IRPV header */
    const val PANO_WIDTH = DEFAULT_PANO_WIDTH

    /** @deprecated use [ParsedFrame.height] from IRPV header */
    const val PANO_HEIGHT = DEFAULT_PANO_HEIGHT

    data class ParsedFrame(
        val sequence: Int,
        val timestampUs: Long,
        val width: Int,
        val height: Int,
        val rawPixels: ShortArray,
        val cameraHealth: ByteArray?,
    )

    fun parse(packet: ByteArray): ParsedFrame? {
        if (packet.size < HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != MAGIC) return null
        val version = buf.get().toInt() and 0xFF
        if (version != 1 && version != 2) return null
        val flags = buf.get().toInt() and 0xFF
        val width = buf.short.toInt() and 0xFFFF
        val height = buf.short.toInt() and 0xFFFF
        val sequence = buf.int
        val timestampUs = buf.long
        val payloadBytes = width * height * 2
        val trailerBytes = if ((flags and FLAG_PER_CAMERA_HEALTH) != 0) 4 else 0
        if (packet.size < HEADER_BYTES + payloadBytes + trailerBytes) return null
        val raw = ShortArray(width * height)
        for (i in raw.indices) {
            raw[i] = buf.short
        }
        val health = if (trailerBytes > 0) {
            ByteArray(trailerBytes).also { b ->
                for (i in b.indices) {
                    b[i] = buf.get()
                }
            }
        } else {
            null
        }
        return ParsedFrame(sequence, timestampUs, width, height, raw, health)
    }
}
