package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HikDynamicTemperatureCompensationTest {

    @Before
    fun resetCompensation() {
        HikDynamicTemperatureCompensation.resetAll()
    }

    @Test
    fun chainSolve_propagatesOffsetsAcrossCameras() {
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744502",
            frame = syntheticFrame(20.1),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744497",
            frame = syntheticFrame(19.5),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744507",
            frame = syntheticFrame(19.0),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )
        val cam4Snapshot = HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744486",
            frame = syntheticFrame(18.5),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )

        assertEquals(0.6, HikDynamicTemperatureCompensation.currentOffsetC("EA6744497"), 0.05)
        assertEquals(1.1, HikDynamicTemperatureCompensation.currentOffsetC("EA6744507"), 0.05)
        assertEquals(1.6, HikDynamicTemperatureCompensation.currentOffsetC("EA6744486"), 0.05)
        assertEquals(1.6, cam4Snapshot.pairDelta34C ?: 0.0, 0.05)
    }

    @Test
    fun chainSolve_handlesNegativeDelta() {
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744502",
            frame = syntheticFrame(19.0),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744497",
            frame = syntheticFrame(20.0),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )

        assertEquals(-1.0, HikDynamicTemperatureCompensation.currentOffsetC("EA6744497"), 0.05)
    }

    @Test
    fun chainSolve_capsOffsetsAtPlusMinusTwoCelsius() {
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744502",
            frame = syntheticFrame(20.0),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744497",
            frame = syntheticFrame(10.0),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744507",
            frame = syntheticFrame(9.0),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )
        HikDynamicTemperatureCompensation.updateFromFrame(
            serial = "EA6744486",
            frame = syntheticFrame(8.0),
            overlapColumnsN = 5,
            centerBandRatio = 0.5,
        )

        assertEquals(2.0, HikDynamicTemperatureCompensation.currentOffsetC("EA6744497"), 0.001)
        assertEquals(2.0, HikDynamicTemperatureCompensation.currentOffsetC("EA6744507"), 0.001)
        assertEquals(2.0, HikDynamicTemperatureCompensation.currentOffsetC("EA6744486"), 0.001)
    }

    private fun syntheticFrame(celsius: Double): ByteArray {
        val frame = ByteArray(HikUvcWireLayout.PAYLOAD_BYTES)
        val raw = ((celsius + HikTherm.KELVIN_OFFSET_C) * HikTherm.TEMP_SCALE - HikTherm.TEMP_BIAS_U16)
            .toInt() and 0xFFFF
        for (y in 0 until HikTherm.GRID_HEIGHT) {
            for (x in 0 until HikTherm.GRID_WIDTH) {
                HikUvcWireLayout.writeRadioRaw16(frame, y, x, raw)
            }
        }
        return frame
    }
}
