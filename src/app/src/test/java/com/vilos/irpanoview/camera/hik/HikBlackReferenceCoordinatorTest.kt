package com.vilos.irpanoview.camera.hik

import org.junit.After
import org.junit.Test

class HikBlackReferenceCoordinatorTest {

    @After
    fun tearDown() {
        HikBlackReferenceCoordinator.resetForTests()
    }

    @Test
    fun onStreaming_ignoredWhenPathNotExpected() {
        HikBlackReferenceCoordinator.syncExpected(setOf("/dev/bus/usb/001/016"))
        HikBlackReferenceCoordinator.onStreaming("/dev/bus/usb/001/099")
        // No crash; coordinator only tracks expected registry paths.
    }

    @Test
    fun releaseClearsExpectedPath() {
        val path = "/dev/bus/usb/001/016"
        HikBlackReferenceCoordinator.syncExpected(setOf(path))
        HikBlackReferenceCoordinator.onReleased(path)
        HikBlackReferenceCoordinator.syncExpected(emptySet())
    }
}
