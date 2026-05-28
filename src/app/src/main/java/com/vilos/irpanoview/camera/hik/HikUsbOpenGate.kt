package com.vilos.irpanoview.camera.hik

import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes [UsbManager.openDevice] + claim + Hik login/bind across cameras.
 * Parallel SELECT 0x500 after exit wedged all 4 cams in logs (build 0525-2140).
 */
object HikUsbOpenGate {

    private val openLock = ReentrantLock(true)

    fun isOpenLocked(): Boolean = openLock.isLocked

    /** Wait for an in-flight open/claim to finish before Exit snapshot. */
    fun waitForIdle(maxMs: Long) {
        val deadline = System.currentTimeMillis() + maxMs
        while (isOpenLocked() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    fun <T> runOpenExclusive(block: () -> T): T {
        openLock.lockInterruptibly()
        try {
            return block()
        } finally {
            if (openLock.isHeldByCurrentThread) {
                openLock.unlock()
            }
        }
    }

    fun resetForTests() {
        while (openLock.isHeldByCurrentThread) {
            openLock.unlock()
        }
    }
}
