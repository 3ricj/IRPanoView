package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Test

class HikIrConfigSettingsTest {

    @Test
    fun defaultWireEncodings_matchSetIrConfigDefaults() {
        HikIrConfigSettings.set(
            HikIrConfigSettings.DEFAULT_EMISSIVITY,
            HikIrConfigSettings.DEFAULT_DISTANCE_M,
            HikIrConfigSettings.DEFAULT_AMBIENT_C,
        )
        assertEquals(95, HikIrConfigSettings.emissivityWire())
        assertEquals(1000, HikIrConfigSettings.distanceWire())
        assertEquals(12_000, HikIrConfigSettings.ambientWire())
    }
}
