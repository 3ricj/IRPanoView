package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Test

class HikUvcStreamTest {

    @Test
    fun patchProbe_setsYuy2FormatFrame9FpsAndMaxFrameSize() {
        val probe = ByteArray(34)
        HikUvcStream.patchProbeFromVideoParam(probe)
        assertEquals(1, probe[2].toInt() and 0xFF)
        assertEquals(9, probe[3].toInt() and 0xFF)
        assertEquals(10_000_000 / HikUvcConstants.STREAM_FPS, readU32(probe, 4))
        assertEquals(HikTherm.FRAME_BYTES, readU32(probe, 22))
    }

    private fun readU32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}
