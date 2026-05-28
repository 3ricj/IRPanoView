package com.vilos.irpanoview.camera

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalLevelRegistryTest {

    private val busPath = "/test/cam-window-smooth"
    private val busPathB = "/test/cam-window-smooth-b"

    @After
    fun tearDown() {
        ThermalLevelRegistry.setFocusedBusPath(null)
        ThermalLevelRegistry.unregister(busPath)
        ThermalLevelRegistry.unregister(busPathB)
    }

    @Test
    fun reportWindow_averagesLastFiveFramesPerCamera() {
        val samples = listOf(
            20.0 to 30.0,
            21.0 to 31.0,
            22.0 to 32.0,
            23.0 to 33.0,
            24.0 to 34.0,
            30.0 to 40.0,
        )
        for ((minC, maxC) in samples) {
            ThermalLevelRegistry.reportWindow(busPath, minC, maxC)
        }

        val window = ThermalLevelRegistry.windowFor(busPath)!!
        assertEquals(24.0, window.first, 0.001)
        assertEquals(34.0, window.second, 0.001)
    }

    @Test
    fun displayWindow_usesGlobalMinMaxAcrossCamerasInGridMode() {
        ThermalLevelRegistry.reportWindow(busPath, 20.0, 30.0)
        ThermalLevelRegistry.reportWindow(busPathB, 25.0, 40.0)

        val window = ThermalLevelRegistry.displayWindow()
        assertEquals(20.0, window.minCelsius, 0.001)
        assertEquals(40.0, window.maxCelsius, 0.001)
    }

    @Test
    fun displayWindow_usesFocusedCameraOnlyInSingleCameraView() {
        ThermalLevelRegistry.reportWindow(busPath, 20.0, 30.0)
        ThermalLevelRegistry.reportWindow(busPathB, 25.0, 40.0)
        ThermalLevelRegistry.setFocusedBusPath(busPath)

        val window = ThermalLevelRegistry.displayWindow()
        assertEquals(20.0, window.minCelsius, 0.001)
        assertEquals(30.0, window.maxCelsius, 0.001)
    }

    @Test
    fun displayWindowFor_usesPerCameraWindowWhenFocused() {
        ThermalLevelRegistry.reportWindow(busPath, 20.0, 30.0)
        ThermalLevelRegistry.reportWindow(busPathB, 25.0, 40.0)
        ThermalLevelRegistry.setFocusedBusPath(busPathB)

        val window = ThermalLevelRegistry.displayWindowFor(busPathB)
        assertEquals(25.0, window.minCelsius, 0.001)
        assertEquals(40.0, window.maxCelsius, 0.001)
    }
}
