package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Offline parity check for wire decode (macropixel radio @ wire row 0).
 */
class HikWireCaptureDecodeTest {

    @Before
    fun resetGridTuning() {
        HikGridDecodeTuning.set(0, 0)
    }

    @Test
    fun syntheticWire_macropixelDecode() {
        val wire = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 21.0)

        val cx = HikTherm.GRID_WIDTH / 2
        val cy = HikTherm.GRID_HEIGHT / 2
        val macRaw = HikUvcWireLayout.readRadioRaw16(wire, cy, cx)
        assertEquals(macRaw, HikTherm.rawU16AtPixel(wire, cx, cy))
        assertTrue(HikTherm.looksLikeRadiometricWire(wire))

        val centerC = HikTherm.celsiusFromRawU16(macRaw)
        assertTrue(HikTherm.isPlausibleCelsius(centerC))
    }
}
