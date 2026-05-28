package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HikMultiCamPolicyTest {

    @Test
    fun sortsByCameraOrderWhenSerialsKnown() {
        val paths = HikMultiCamPolicy.sortedBusPathsByCameraOrder(
            listOf(
                "/dev/bus/usb/001/018" to "EA6744486",
                "/dev/bus/usb/001/016" to "EA6473497",
                "/dev/bus/usb/001/025" to "EA6744502",
                "/dev/bus/usb/001/024" to "EA6744407",
                "/dev/bus/usb/001/026" to null,
            ),
        )
        assertEquals(
            listOf(
                "/dev/bus/usb/001/025",
                "/dev/bus/usb/001/016",
                "/dev/bus/usb/001/024",
                "/dev/bus/usb/001/018",
                "/dev/bus/usb/001/026",
            ),
            paths,
        )
        assertEquals(0, HikMultiCamPolicy.streamRank("/dev/bus/usb/001/025", paths))
        assertEquals(1, HikMultiCamPolicy.streamRank("/dev/bus/usb/001/016", paths))
        assertFalse(HikMultiCamPolicy.isStreamEnabled("/dev/bus/usb/001/026", paths))
    }

    @Test
    fun enablesLowestBusPathsFirstWhenSerialUnknown() {
        val paths = listOf(
            "/dev/bus/usb/001/018",
            "/dev/bus/usb/001/016",
            "/dev/bus/usb/001/025",
            "/dev/bus/usb/001/024",
            "/dev/bus/usb/001/026",
        )
        val sorted = HikMultiCamPolicy.sortedBusPaths(paths)
        assertTrue(HikMultiCamPolicy.isStreamEnabled("/dev/bus/usb/001/016", sorted))
        assertTrue(HikMultiCamPolicy.isStreamEnabled("/dev/bus/usb/001/018", sorted))
        assertTrue(HikMultiCamPolicy.isStreamEnabled("/dev/bus/usb/001/024", sorted))
        assertTrue(HikMultiCamPolicy.isStreamEnabled("/dev/bus/usb/001/025", sorted))
        assertFalse(HikMultiCamPolicy.isStreamEnabled("/dev/bus/usb/001/026", sorted))
    }
}
