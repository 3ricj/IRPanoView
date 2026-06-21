package com.vilos.irpanoview.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PanoFrameDecoderTest {

    @Test
    fun syntheticDemoFrame_hasExpectedDimensions() {
        val raw = PanoFrameDecoder.syntheticDemoFrame(width = 1024, height = 192, phase = 0)
        assertEquals(1024 * 192, raw.size)
    }

    @Test
    fun syntheticDemoFrame_valuesAreInValidRawRange() {
        val raw = PanoFrameDecoder.syntheticDemoFrame(width = 256, height = 192, phase = 3)
        assert(raw.all { (it.toInt() and 0xFFFF) in 0..65535 })
    }
}
