package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HikThermTest {

    @Before
    fun resetGridTuning() {
        HikGridDecodeTuning.set(0, 0)
    }

    @Test
    fun celsiusFromRaw_roundTripThroughBias() {
        val stored = 18_762
        val raw = (stored - HikTherm.TEMP_BIAS_U16) and 0xFFFF
        assertEquals(stored, HikTherm.storedU16FromRaw(raw))
        assertEquals(20.0, HikTherm.celsiusFromRawU16(raw), 0.05)
    }

    @Test
    fun frameClassification() {
        assertEquals("hik_uvc_wire", HikTherm.classifyFrameBytes(HikTherm.FRAME_BYTES))
        assertFalse(HikTherm.isUvcWireFrame(ByteArray(100)))
        assertTrue(HikTherm.isUvcWireFrame(ByteArray(HikTherm.FRAME_BYTES)))
    }

    @Test
    fun rawU16AtPixel_readsMacropixelBand() {
        val frame = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame()
        val x = 10
        val y = 20
        HikUvcWireLayout.writeRadioRaw16(frame, y, x, 0x1234)
        assertEquals(0x1234, HikTherm.rawU16AtPixel(frame, x, y))
    }
}
