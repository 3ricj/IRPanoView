package com.vilos.irpanoview.camera.hik

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes bulk IN when many cameras share one hub controller.
 * Parallel bulk while active workers ≤ [HikMultiCamPolicy.ACTIVE_STREAM_LIMIT];
 * serialize bulk IN when above that.
 */
object HikBulkGate {

    private val bulkLock = ReentrantLock(true)
    private val streamingWorkers = AtomicInteger(0)

    /** Parallel bulk through [HikMultiCamPolicy.ACTIVE_STREAM_LIMIT]; serialize above that. */
    private val parallelBulkMaxStreams: Int
        get() = HikMultiCamPolicy.ACTIVE_STREAM_LIMIT

    fun useParallelBulk(): Boolean =
        streamingWorkers.get() <= parallelBulkMaxStreams

    fun registerStreamingWorker() {
        streamingWorkers.incrementAndGet()
    }

    fun unregisterStreamingWorker() {
        streamingWorkers.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun activeStreamingWorkers(): Int = streamingWorkers.get()

    fun <T> runBulkExclusive(block: () -> T): T {
        if (useParallelBulk()) return block()
        bulkLock.lockInterruptibly()
        try {
            return block()
        } finally {
            if (bulkLock.isHeldByCurrentThread) {
                bulkLock.unlock()
            }
        }
    }

    fun resetForTests() {
        streamingWorkers.set(0)
        while (bulkLock.isHeldByCurrentThread) {
            bulkLock.unlock()
        }
    }
}
