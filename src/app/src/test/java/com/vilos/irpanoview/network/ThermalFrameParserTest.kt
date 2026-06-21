package com.vilos.irpanoview.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ThermalFrameParserTest {

    @Test
    fun parse_roundTripsHeaderAndPayload() {
        val width = 4
        val height = 2
        val pixels = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val packet = ByteBuffer.allocate(22 + pixels.size * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(ThermalFrameParser.MAGIC)
        packet.put(1)
        packet.put(ThermalFrameParser.FLAG_PER_CAMERA_HEALTH.toByte())
        packet.putShort(width.toShort())
        packet.putShort(height.toShort())
        packet.putInt(42)
        packet.putLong(99L)
        pixels.forEach { packet.putShort(it) }
        packet.put(byteArrayOf(1, 1, 0, 1))

        val parsed = ThermalFrameParser.parse(packet.array())
        assertNotNull(parsed)
        assertEquals(42, parsed!!.sequence)
        assertEquals(99L, parsed.timestampUs)
        assertArrayEquals(pixels, parsed.rawPixels)
        assertEquals(4, parsed.cameraHealth?.size)
    }
}
