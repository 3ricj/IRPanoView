package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertTrue
import org.junit.Test

class HikFrameDecoderTest {

    @Test
    fun scanWindow_usesFullSampleSetWithoutPlausibilityGate() {
        val frame = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 20.0)
        val policy = HikFrameDecoder.DynamicWindowPolicy(
            hardMinCelsius = 10.0,
            hardMaxCelsius = 50.0,
        )
        val scan = HikFrameDecoder.scanCelsiusWindow(frame, policy)
        assertTrue(scan.sampleCount == HikTherm.GRID_SAMPLES)
        assertTrue(scan.windowMaxC > scan.windowMinC)
    }
}
