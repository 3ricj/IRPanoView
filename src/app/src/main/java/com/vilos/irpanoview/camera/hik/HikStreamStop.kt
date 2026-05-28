package com.vilos.irpanoview.camera.hik

import android.content.Context
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exit stop sequence — goal: [HikBulkInstrumentation.reportStopOutcome] == BULK_STOP_QUIET.
 */
object HikStreamStop {

    /** Legacy short join — prefer [HikShutdownExperiment.SOFT_PAUSE_JOIN_MS] for reference soft pause. */
    const val WORKER_JOIN_MS = 800L

    /** Full-exit / legacy E8 cancel wait up to 3 s (matches libuvc). */
    const val STOP_CHANNEL_JOIN_MS = 3_000L

    /**
     * Cancel → stop flag → join worker. Retries once if bulk not quiet (legacy recipes only).
     * Caller proceeds with teardown even if this returns false ([requireWorkerDead] = false).
     */
    fun stopChannel(
        logContext: Context,
        busPath: String,
        stopRequested: AtomicBoolean,
        worker: Thread?,
        streaming: () -> Boolean,
        clearStreaming: () -> Unit,
        workerJoinMs: Long = STOP_CHANNEL_JOIN_MS,
        cancelBulk: (() -> Unit)? = null,
        workerJoinRetry: Boolean = true,
        requireWorkerDead: Boolean = true,
    ): Boolean {
        cancelBulk?.invoke()
        HikBulkInstrumentation.markStopRequested(logContext, busPath, "stopChannel")
        stopRequested.set(true)
        if (streaming()) clearStreaming()
        worker?.interrupt()
        worker?.join(workerJoinMs)
        if (worker?.isAlive == true) {
            if (requireWorkerDead) {
                UvcDebugLogger.log(logContext, busPath, "SHUTDOWN worker alive after join — extra interrupt")
                worker.interrupt()
                worker.join(2_000L)
                if (worker.isAlive) {
                    UvcDebugLogger.log(logContext, busPath, "SHUTDOWN worker still alive after retry — proceed teardown")
                }
            } else {
                UvcDebugLogger.log(logContext, busPath, "SHUTDOWN worker alive after ${workerJoinMs}ms — proceed teardown")
            }
        }
        var quiet = reportOutcome(logContext, busPath, "stopChannel")
        if (!quiet && workerJoinRetry) {
            UvcDebugLogger.log(logContext, busPath, "BULK_STOP_RETRY cancel+join")
            cancelBulk?.invoke()
            worker?.interrupt()
            worker?.join(workerJoinMs)
            quiet = reportOutcome(logContext, busPath, "stopChannel-retry")
        }
        if (worker?.isAlive == true) {
            UvcDebugLogger.log(
                logContext,
                busPath,
                "BULK_STOP_WORKER_ALIVE label=stopChannel proceed=$quiet",
            )
        }
        return quiet
    }

    private fun reportOutcome(logContext: Context, busPath: String, label: String): Boolean =
        HikBulkInstrumentation.reportStopOutcome(logContext, busPath, label)
}
