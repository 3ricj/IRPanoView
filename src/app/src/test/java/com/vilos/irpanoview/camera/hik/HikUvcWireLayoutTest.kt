package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HikUvcWireLayoutTest {

    @Test
    fun readRadioRaw16_macropixelRoundTrip() {
        val frame = ByteArray(HikUvcWireLayout.PAYLOAD_BYTES)
        HikUvcWireLayout.writeRadioRaw16(frame, 10, 20, 0xABCD)
        assertEquals(0xABCD, HikUvcWireLayout.readRadioRaw16(frame, 10, 20))
        HikUvcWireLayout.writeRadioRaw16(frame, 10, 21, 0x1234)
        assertEquals(0x1234, HikUvcWireLayout.readRadioRaw16(frame, 10, 21))
    }

    @Test
    fun footerBytes_extractFixedSlices() {
        val wire = buildSyntheticUvcWireFrame(centerC = 22.0)
        wire[HikUvcWireLayout.FOOTER1_OFFSET] = 0xAB.toByte()
        wire[HikUvcWireLayout.FOOTER2_OFFSET] = '['.code.toByte()

        val f1 = HikUvcWireLayout.footer1Bytes(wire)
        val f2 = HikUvcWireLayout.footer2Bytes(wire)
        assertEquals(HikUvcWireLayout.FOOTER1_BYTES, f1.size)
        assertEquals(HikUvcWireLayout.FOOTER2_BYTES, f2.size)
        assertEquals(0xAB, f1[0].toInt() and 0xFF)
        assertEquals('['.code, f2[0].toInt() and 0xFF)
    }

    @Test
    fun looksLikeWireRadioBand_plausibleGrid() {
        val wire = buildSyntheticUvcWireFrame(centerC = 22.0)
        assertTrue(HikUvcWireLayout.looksLikeWireRadioBand(wire))
        assertTrue(HikTherm.looksLikeRadiometricWire(wire))
    }

    companion object {
        fun buildSyntheticUvcWireFrame(centerC: Double = 22.0): ByteArray {
            val frame = ByteArray(HikUvcWireLayout.PAYLOAD_BYTES)
            val raw = ((centerC + HikTherm.KELVIN_OFFSET_C) * HikTherm.TEMP_SCALE - HikTherm.TEMP_BIAS_U16)
                .toInt() and 0xFFFF
            for (y in 0 until HikTherm.GRID_HEIGHT) {
                for (x in 0 until HikTherm.GRID_WIDTH) {
                    HikUvcWireLayout.writeRadioRaw16(frame, y, x, raw)
                }
            }
            HikUvcWireLayout.writeRadioRaw16(frame, 10, 20, 0xABCD)
            return frame
        }
    }
}
