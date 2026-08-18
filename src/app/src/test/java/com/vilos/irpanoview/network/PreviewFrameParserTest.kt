package com.vilos.irpanoview.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PreviewFrameParserTest {

    @Test
    fun parse_roundTripsHeaderAndPixels() {
        val width = 4
        val height = 2
        val pixels = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val packet = ByteBuffer.allocate(PreviewFrameParser.HEADER_BYTES + pixels.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(PreviewFrameParser.MAGIC)
        packet.putShort(width.toShort())
        packet.putShort(height.toShort())
        packet.putInt(99)
        packet.putLong(123456789L)
        packet.putFloat(12.5f)
        packet.putFloat(42.0f)
        packet.put(pixels)

        val parsed = PreviewFrameParser.parse(packet.array())
        assertNotNull(parsed)
        assertEquals(4, parsed!!.width)
        assertEquals(2, parsed.height)
        assertEquals(99, parsed.sequence)
        assertEquals(123456789L, parsed.timestampUs)
        assertEquals(12.5f, parsed.minC, 0.001f)
        assertEquals(42.0f, parsed.maxC, 0.001f)
        assertArrayEquals(pixels, parsed.pixels)
    }

    @Test
    fun parse_rejectsBadMagic() {
        val packet = ByteBuffer.allocate(PreviewFrameParser.HEADER_BYTES + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(0x12345678)
        packet.putShort(1)
        packet.putShort(1)
        packet.putInt(0)
        packet.putLong(0)
        packet.putFloat(0f)
        packet.putFloat(1f)
        packet.put(0)
        assertNull(PreviewFrameParser.parse(packet.array()))
    }

    @Test
    fun readIrprPacket_roundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val framed = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        framed.putInt(PreviewStreamReceiver.IRPR_MAGIC)
        framed.putInt(payload.size)
        framed.put(payload)
        val read = PreviewStreamReceiver.readIrprPacket(ByteArrayInputStream(framed.array()))
        assertNotNull(read)
        assertArrayEquals(payload, read)
    }
}
