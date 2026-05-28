package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HikRadiometricCalibrationTest {

    @Test
    fun knownQuadSerialsHaveZeroOffset() {
        assertEquals(0.0, HikRadiometricCalibration.offsetCelsius("EA6744502"), 0.001)
        assertEquals(0.0, HikRadiometricCalibration.offsetCelsius("EA6744407"), 0.001)
        assertEquals(0.0, HikRadiometricCalibration.offsetCelsius("EA6462986"), 0.001)
        assertEquals(0.0, HikRadiometricCalibration.offsetCelsius("EA6473497"), 0.001)
    }

    @Test
    fun unknownSerialHasZeroOffset() {
        assertEquals(0.0, HikRadiometricCalibration.offsetCelsius("UNKNOWN123"), 0.001)
        assertEquals(0.0, HikRadiometricCalibration.offsetCelsius(null), 0.001)
        assertFalse(HikRadiometricCalibration.isCalibrated(null))
    }

    @Test
    fun marksKnownSerialsCalibrated() {
        assertTrue(HikRadiometricCalibration.isCalibrated("EA6744407"))
    }

    @Test
    fun biasDeltaU16_isZeroForAllKnownSerials() {
        assertEquals(0, HikRadiometricCalibration.biasDeltaU16("EA6744502"))
        assertEquals(0, HikRadiometricCalibration.biasDeltaU16("EA6744407"))
        assertEquals(0, HikRadiometricCalibration.biasDeltaU16("EA6462986"))
        assertEquals(0, HikRadiometricCalibration.biasDeltaU16("EA6473497"))
    }
}
