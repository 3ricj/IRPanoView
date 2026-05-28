package com.vilos.irpanoview.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureModelTest {

    @Test
    fun tc001Model_usesStrictRuntimeScale16Formula() {
        val model = TemperatureModel.apkRuntimeReimplV1Tc001()
        val low = model.rawToCelsius(32799)
        val mid = model.rawToCelsius(32840)
        val high = model.rawToCelsius(32984)

        assertEquals((32799.0 / 16.0) - 273.15, low, 0.0001)
        assertEquals((32840.0 / 16.0) - 273.15, mid, 0.0001)
        assertEquals((32984.0 / 16.0) - 273.15, high, 0.0001)
        assertTrue(low < mid)
        assertTrue(mid < high)
    }

    @Test
    fun tc001Registry_usesRuntimeModelForTopdonTc001() {
        val model = TemperatureModelRegistry.forUsbDevice(0x2BDF, 0x0102)
        assertEquals("apk_runtime_reimpl_v1_tc001", model.name)
    }
}
