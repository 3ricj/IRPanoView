package com.vilos.irpanoview.model

import com.vilos.irpanoview.camera.hik.HikMultiCamPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class QuadCameraOrderTest {

    @Test
    fun mapsSerialSuffixesToCameraNumbers() {
        assertEquals(1, QuadCameraOrder.cameraNumber("EA6744502"))
        assertEquals(2, QuadCameraOrder.cameraNumber("EA6473497"))
        assertEquals(3, QuadCameraOrder.cameraNumber("EA6744407"))
        assertEquals(4, QuadCameraOrder.cameraNumber("EA6744486"))
    }

    @Test
    fun assignsGridCellsInTitleOrder() {
        assertEquals(0, QuadCameraOrder.cellIndex("EA6744502"))
        assertEquals(1, QuadCameraOrder.cellIndex("EA6473497"))
        assertEquals(2, QuadCameraOrder.cellIndex("EA6744407"))
        assertEquals(3, QuadCameraOrder.cellIndex("EA6744486"))
    }

    @Test
    fun sortsBusPathsByCameraOrder() {
        val paths = HikMultiCamPolicy.sortedBusPathsByCameraOrder(
            listOf(
                "/dev/bus/usb/001/025" to "EA6744502",
                "/dev/bus/usb/001/016" to "EA6473497",
                "/dev/bus/usb/001/024" to "EA6744407",
                "/dev/bus/usb/001/018" to "EA6744486",
            ),
        )
        assertEquals(
            listOf(
                "/dev/bus/usb/001/025",
                "/dev/bus/usb/001/016",
                "/dev/bus/usb/001/024",
                "/dev/bus/usb/001/018",
            ),
            paths,
        )
    }
}
