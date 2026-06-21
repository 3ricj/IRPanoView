package com.vilos.irpanoview.model

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
    fun sortsSerialsByCameraOrder() {
        val serials = listOf("EA6744486", "EA6744502", "EA6473497", "EA6744407")
            .sortedBy { QuadCameraOrder.sortKey(it) }
        assertEquals(
            listOf("EA6744502", "EA6473497", "EA6744407", "EA6744486"),
            serials,
        )
    }
}
