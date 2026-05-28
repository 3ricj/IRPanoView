package com.vilos.irpanoview.camera.hik

import android.content.Context
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * USB bulk IN instrumentation — fixed log vocabulary for Exit/stop cycles.
 *
 * Grep: `BULK_STOP_REQUESTED|BULK_IN |BULK_STOP_QUIET|BULK_STOP_FAILED`
 *
 * Success criterion (reference wire): after [BULK_STOP_REQUESTED], no [BULK_IN] for
 * [QUIET_MS] and [inflightBulkIn] == 0.
 */
object HikBulkInstrumentation {

    /** Reference StopChannel quiet window is ~10 ms; allow margin on Android. */
    const val QUIET_MS = 50L

    private data class PathState(
        val stopRequestedAtMs: AtomicLong = AtomicLong(0L),
        val lastBulkInAtMs: AtomicLong = AtomicLong(0L),
        val bulkInTotal: AtomicLong = AtomicLong(0L),
        val bulkInSinceStop: AtomicLong = AtomicLong(0L),
        val inflightBulkIn: AtomicInteger = AtomicInteger(0),
    )

    private val byPath = ConcurrentHashMap<String, PathState>()

    fun reset(busPath: String) {
        byPath.remove(busPath)
    }

    fun markStopRequested(logContext: Context, busPath: String, reason: String) {
        val state = byPath.getOrPut(busPath) { PathState() }
        state.stopRequestedAtMs.set(System.currentTimeMillis())
        state.bulkInSinceStop.set(0L)
        UvcDebugLogger.log(
            logContext,
            busPath,
            "BULK_STOP_REQUESTED reason=$reason bulkInTotal=${state.bulkInTotal.get()} " +
                "inflight=${state.inflightBulkIn.get()}",
        )
    }

    fun onBulkInEnter(logContext: Context?, busPath: String, ep: Int, timeoutMs: Int, stopRequested: Boolean) {
        val state = byPath.getOrPut(busPath) { PathState() }
        state.inflightBulkIn.incrementAndGet()
        if (stopRequested) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(
                    ctx,
                    busPath,
                    "BULK_IN enter ep=${ep.toString(16)} timeout=${timeoutMs}ms stop=true " +
                        "inflight=${state.inflightBulkIn.get()}",
                )
            }
        }
    }

    fun onBulkInComplete(
        logContext: Context?,
        busPath: String,
        ep: Int,
        timeoutMs: Int,
        rc: Int,
        elapsedMs: Long,
        stopRequested: Boolean,
    ) {
        val state = byPath.getOrPut(busPath) { PathState() }
        state.inflightBulkIn.updateAndGet { (it - 1).coerceAtLeast(0) }
        val now = System.currentTimeMillis()
        state.lastBulkInAtMs.set(now)
        state.bulkInTotal.incrementAndGet()
        if (state.stopRequestedAtMs.get() > 0L) {
            state.bulkInSinceStop.incrementAndGet()
        }
        logContext?.let { ctx ->
            val stopWindow = state.stopRequestedAtMs.get() > 0L
            if (stopRequested || stopWindow) {
                UvcDebugLogger.log(
                    ctx,
                    busPath,
                    "BULK_IN ep=${ep.toString(16)} timeout=${timeoutMs}ms rc=$rc elapsed=${elapsedMs}ms " +
                        "stop=$stopRequested inflight=${state.inflightBulkIn.get()} " +
                        "sinceStop=${state.bulkInSinceStop.get()}",
                )
            }
        }
    }

    /** @return true when bulk IN has been quiet long enough (reference StopChannel outcome). */
    fun reportStopOutcome(logContext: Context, busPath: String, label: String): Boolean {
        val state = byPath[busPath] ?: PathState()
        val now = System.currentTimeMillis()
        val stopAt = state.stopRequestedAtMs.get()
        val msSinceStop = if (stopAt > 0L) now - stopAt else -1L
        val msSinceLastBulk = if (state.lastBulkInAtMs.get() > 0L) {
            now - state.lastBulkInAtMs.get()
        } else {
            -1L
        }
        val inflight = state.inflightBulkIn.get()
        val sinceStop = state.bulkInSinceStop.get()

        val quiet = inflight == 0 && stopAt > 0L && sinceStop == 0L

        val failed = inflight > 0 || sinceStop > 0L

        when {
            quiet -> UvcDebugLogger.log(
                logContext,
                busPath,
                "BULK_STOP_QUIET label=$label msSinceStop=$msSinceStop msSinceLastBulkIn=$msSinceLastBulk",
            )
            failed -> UvcDebugLogger.log(
                logContext,
                busPath,
                "BULK_STOP_FAILED label=$label msSinceStop=$msSinceStop inflight=$inflight " +
                    "bulkInSinceStop=$sinceStop msSinceLastBulkIn=$msSinceLastBulk",
            )
            else -> UvcDebugLogger.log(
                logContext,
                busPath,
                "BULK_STOP_PENDING label=$label msSinceStop=$msSinceStop inflight=$inflight " +
                    "bulkInSinceStop=$sinceStop msSinceLastBulkIn=$msSinceLastBulk",
            )
        }
        return quiet
    }
}
