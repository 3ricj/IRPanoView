package com.vilos.irpanoview.camera.hik

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class HikUsbStackRecoveryTest {

    @After
    fun tearDown() {
        HikUsbStackRecovery.resetForTests()
        HikCameraRegistry.resetForTests()
    }

    @Test
    fun openTimeoutsTripFault() {
        assertFalse(HikUsbStackRecovery.isFaulted())
        HikUsbStackRecovery.recordOpenTimeout("/dev/bus/usb/001/008")
        assertFalse(HikUsbStackRecovery.isFaulted())
        HikUsbStackRecovery.recordOpenTimeout("/dev/bus/usb/001/009")
        assertTrue(HikUsbStackRecovery.isFaulted())
    }

    @Test
    fun hubDetachClearsFault() {
        HikUsbStackRecovery.recordOpenTimeout("/dev/bus/usb/001/008")
        HikUsbStackRecovery.recordOpenTimeout("/dev/bus/usb/001/009")
        assertTrue(HikUsbStackRecovery.isFaulted())
        HikUsbStackRecovery.onHubFullyDetached()
        assertFalse(HikUsbStackRecovery.isFaulted())
    }

    @Test
    fun postStopSettleBlocksCheckCanOpen() {
        HikUsbStackRecovery.onHubFullyDetached()
        HikUsbStackRecovery.checkCanOpen("/dev/bus/usb/001/008")

        val until = System.currentTimeMillis() + HikUsbStackRecovery.POST_STOP_SETTLE_MS
        HikUsbStackRecovery.setPostStopSettleUntilForTests(until)

        var blocked = false
        try {
            HikUsbStackRecovery.checkCanOpen("/dev/bus/usb/001/008")
        } catch (e: HikProtocolException) {
            blocked = e.message?.contains("post-stop settling") == true
        }
        assertTrue(blocked)
        assertTrue(HikUsbStackRecovery.postStopSettleRemainMs() > 0L)
    }

    @Test
    fun beginGlobalShutdownBlocksRegistryRelease() {
        assertFalse(HikCameraRegistry.isGlobalShutdownInFlight())
        HikCameraRegistry.beginGlobalShutdown()
        assertTrue(HikCameraRegistry.isGlobalShutdownInFlight())
        HikCameraRegistry.release("/dev/bus/usb/001/003")
        // no crash; release is a no-op while global shutdown is in flight
    }
}

class HikStreamStopTest {

    @Test
    fun stopChannel_joinsWorkerWhenStopRequested() {
        val stopRequested = AtomicBoolean(false)
        val streaming = AtomicBoolean(true)
        val worker = Thread {
            while (!stopRequested.get()) {
                Thread.sleep(20)
            }
        }
        worker.start()
        HikStreamStop.stopChannel(
            logContext = object : android.content.ContextWrapper(null) {},
            busPath = "/dev/bus/usb/001/008",
            stopRequested = stopRequested,
            worker = worker,
            streaming = { streaming.get() },
            clearStreaming = { streaming.set(false) },
        )
        assertFalse(worker.isAlive)
        assertFalse(streaming.get())
    }
}
