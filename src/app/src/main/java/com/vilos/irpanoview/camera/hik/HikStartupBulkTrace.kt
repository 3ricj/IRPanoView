package com.vilos.irpanoview.camera.hik

import android.content.Context
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Verbose bulk IN trace during cold start (until first super-frame per camera).
 *
 * Grep: `STARTUP_BULK` in uvc-debug.log
 */
object HikStartupBulkTrace {

    private data class SliceState(
        val drainMs: Int,
        val startedAtMs: Long = System.currentTimeMillis(),
        var xfers: Int = 0,
        var dataXfers: Int = 0,
        var timeouts: Int = 0,
        var bytesReceived: Long = 0,
        var maxPartialBytes: Int = 0,
        var maxPartialPkts: Int = 0,
    )

    private data class CamState(
        val startedAtMs: Long = System.currentTimeMillis(),
        var totalXfers: Int = 0,
        var totalDataXfers: Int = 0,
        var totalTimeouts: Int = 0,
        var totalBytes: Long = 0,
        var partialDrops: Int = 0,
        var partialBytesDropped: Long = 0,
        var framingEvents: Int = 0,
        var rejects: Int = 0,
        var drainSlices: Int = 0,
        var currentSlice: SliceState? = null,
        var truncatedXfers: Int = 0,
    )

    private const val MAX_DETAIL_XFERS = 250

    private val byPath = ConcurrentHashMap<String, CamState>()

    fun isActive(busPath: String): Boolean = byPath.containsKey(busPath)

    fun begin(context: Context?, busPath: String) {
        byPath[busPath] = CamState()
        log(context, busPath, "BEGIN framing=${HikBulkFramingMode.active.logTag} stream_armed workers=${HikBulkGate.activeStreamingWorkers()}")
    }

    fun end(context: Context?, busPath: String, reason: String) {
        val state = byPath.remove(busPath) ?: return
        finishSlice(context, busPath, state, gotFrame = reason == "first_frame")
        val elapsed = System.currentTimeMillis() - state.startedAtMs
        log(
            context,
            busPath,
            "END reason=$reason elapsed=${elapsed}ms slices=${state.drainSlices} " +
                "xfers=${state.totalXfers} data=${state.totalDataXfers} timeout=${state.totalTimeouts} " +
                "bytes=${state.totalBytes} partial_drops=${state.partialDrops} " +
                "partial_bytes_dropped=${state.partialBytesDropped} framing=${state.framingEvents} " +
                "rejects=${state.rejects} truncated_xfers=${state.truncatedXfers}",
        )
    }

    fun beginDrainSlice(context: Context?, busPath: String, drainMs: Int) {
        val state = byPath[busPath] ?: return
        finishSlice(context, busPath, state, gotFrame = false)
        state.drainSlices++
        state.currentSlice = SliceState(drainMs = drainMs)
        log(context, busPath, "drain_begin budget=${drainMs}ms slice#${state.drainSlices}")
    }

    fun onXfer(
        context: Context?,
        busPath: String,
        rc: Int,
        timeoutMs: Int,
        elapsedMs: Long,
        partialBytes: Int,
        partialPkts: Int,
        uvcBm: Int? = null,
    ) {
        val state = byPath[busPath] ?: return
        val slice = state.currentSlice
        state.totalXfers++
        slice?.xfers = (slice?.xfers ?: 0) + 1

        if (rc > 0) {
            state.totalDataXfers++
            state.totalBytes += rc
            slice?.let {
                it.dataXfers++
                it.bytesReceived += rc
            }
        } else if (rc < 0) {
            state.totalTimeouts++
            slice?.timeouts = (slice?.timeouts ?: 0) + 1
        }

        updatePartialPeaks(state, slice, partialBytes, partialPkts)

        val detail = state.totalXfers <= MAX_DETAIL_XFERS
        if (!detail) {
            state.truncatedXfers++
            if (state.truncatedXfers % 50 == 1) {
                log(
                    context,
                    busPath,
                    "xfer_summary +${state.truncatedXfers} more (total=${state.totalXfers}) " +
                        "partial=$partialBytes/${HikTherm.FRAME_BYTES} timeout_total=${state.totalTimeouts}",
                )
            }
            return
        }

        val bm = uvcBm?.let { " bm=${it.toString(16)}" }.orEmpty()
        when {
            rc > 0 -> log(
                context,
                busPath,
                "xfer#${state.totalXfers} rc=$rc t=${timeoutMs}ms e=${elapsedMs}ms " +
                    "partial=$partialBytes/${HikTherm.FRAME_BYTES} pkts=$partialPkts$bm",
            )
            rc < 0 -> log(
                context,
                busPath,
                "xfer#${state.totalXfers} TIMEOUT rc=$rc t=${timeoutMs}ms e=${elapsedMs}ms " +
                    "partial=$partialBytes/${HikTherm.FRAME_BYTES} pkts=$partialPkts",
            )
            else -> log(
                context,
                busPath,
                "xfer#${state.totalXfers} rc=0 t=${timeoutMs}ms e=${elapsedMs}ms " +
                    "partial=$partialBytes/${HikTherm.FRAME_BYTES} pkts=$partialPkts",
            )
        }
    }

    fun endDrainSlice(
        context: Context?,
        busPath: String,
        gotFrame: Boolean,
        droppedPartial: Boolean,
        dropReason: String?,
    ) {
        val state = byPath[busPath] ?: return
        if (droppedPartial) {
            val slice = state.currentSlice
            val bytes = slice?.maxPartialBytes ?: 0
            val pkts = slice?.maxPartialPkts ?: 0
            if (bytes > 0) {
                state.partialDrops++
                state.partialBytesDropped += bytes
                log(
                    context,
                    busPath,
                    "DROP partial=$bytes/${HikTherm.FRAME_BYTES}B pkts=$pkts reason=${dropReason ?: "unknown"}",
                )
            }
        }
        finishSlice(context, busPath, state, gotFrame)
    }

    fun onFraming(context: Context?, busPath: String, kind: String, bytesDropped: Int, headHex: String) {
        val state = byPath[busPath] ?: return
        state.framingEvents++
        log(context, busPath, "FRAMING kind=$kind dropped=$bytesDropped head=$headHex")
    }

    fun onReject(context: Context?, busPath: String, size: Int, headHex: String) {
        val state = byPath[busPath] ?: return
        state.rejects++
        log(context, busPath, "REJECT size=$size head=$headHex")
    }

    fun onFrameComplete(context: Context?, busPath: String, pkts: Int, bytes: Int) {
        val state = byPath[busPath] ?: return
        log(context, busPath, "FRAME_COMPLETE pkts=$pkts bytes=$bytes")
    }

    fun onReArm(context: Context?, busPath: String, attempt: Int, waitedMs: Long) {
        log(context, busPath, "REARM attempt=$attempt waited=${waitedMs}ms")
    }

    fun resetForTests() {
        byPath.clear()
    }

    private fun finishSlice(context: Context?, busPath: String, state: CamState, gotFrame: Boolean) {
        val slice = state.currentSlice ?: return
        state.currentSlice = null
        val elapsed = System.currentTimeMillis() - slice.startedAtMs
        log(
            context,
            busPath,
            "drain_end slice#${state.drainSlices} elapsed=${elapsed}ms budget=${slice.drainMs}ms " +
                "xfers=${slice.xfers} data=${slice.dataXfers} timeout=${slice.timeouts} " +
                "bytes=${slice.bytesReceived} maxPartial=${slice.maxPartialBytes}/${HikTherm.FRAME_BYTES} " +
                "maxPkts=${slice.maxPartialPkts} frame=${if (gotFrame) "yes" else "no"}",
        )
    }

    private fun updatePartialPeaks(state: CamState, slice: SliceState?, partialBytes: Int, partialPkts: Int) {
        if (partialBytes > (slice?.maxPartialBytes ?: 0)) {
            slice?.maxPartialBytes = partialBytes
        }
        if (partialPkts > (slice?.maxPartialPkts ?: 0)) {
            slice?.maxPartialPkts = partialPkts
        }
        if (partialBytes > 0 && slice == null) {
            // xfers outside an active drain slice (poll path)
        }
    }

    private fun log(context: Context?, busPath: String, message: String) {
        val ctx = context ?: return
        val short = busPath.substringAfterLast('/')
        UvcDebugLogger.log(ctx.applicationContext, "hik-bulk-startup", "STARTUP_BULK cam=$short $message")
    }
}
