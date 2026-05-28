package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HikStreamRingTest {

    @Test
    fun takeFrame_emitsExactSizeAndKeepsRemainder() {
        val ring = HikStreamRing()
        val chunk = ByteArray(HikTherm.FRAME_BYTES + 100) { it.toByte() }
        ring.append(chunk, 0, chunk.size)

        val frame = ring.takeFrame(HikTherm.FRAME_BYTES)
        requireNotNull(frame)
        assertEquals(HikTherm.FRAME_BYTES, frame.size)
        assertArrayEquals(chunk.copyOfRange(0, HikTherm.FRAME_BYTES), frame)
        assertEquals(100, ring.bufferedBytes)
    }

    @Test
    fun peekFrameAt_readsWindowWithoutConsume() {
        val ring = HikStreamRing()
        ring.append(ByteArray(10) { it.toByte() }, 0, 10)
        assertEquals(5, ring.peekFrameAt(2, 5)?.size)
        assertEquals(10, ring.bufferedBytes)
    }
}
