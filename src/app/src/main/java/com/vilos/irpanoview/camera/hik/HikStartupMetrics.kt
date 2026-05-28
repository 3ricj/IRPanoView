package com.vilos.irpanoview.camera.hik

import android.content.Context
import com.vilos.irpanoview.BuildConfig
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.ConcurrentHashMap

/** Cold-start phase timestamps for startup optimization A/B (see uvc-debug.log). */
object HikStartupMetrics {

    @Volatile private var sessionStartMs: Long = 0L
    private val perCamPhases = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    fun markProcessStart(context: Context) {
        sessionStartMs = System.currentTimeMillis()
        perCamPhases.clear()
        mark(context, busPath = null, phase = "process_start")
    }

    fun mark(context: Context, busPath: String?, phase: String) {
        val start = sessionStartMs
        if (start <= 0L) {
            sessionStartMs = System.currentTimeMillis()
        }
        val elapsed = System.currentTimeMillis() - sessionStartMs
        if (busPath != null) {
            perCamPhases.computeIfAbsent(busPath) { ConcurrentHashMap() }[phase] = elapsed
        }
        val bus = busPath ?: "-"
        UvcDebugLogger.log(
            context.applicationContext,
            "hik-startup",
            "STARTUP_METRICS build=${BuildConfig.SHUTDOWN_BUILD_STAMP} bus=$bus phase=$phase elapsed=${elapsed}ms",
        )
        if (phase == "all_streaming") {
            logSessionSummary(context)
        }
    }

    private fun logSessionSummary(context: Context) {
        val total = System.currentTimeMillis() - sessionStartMs
        val camSummaries = perCamPhases.entries
            .sortedBy { it.key }
            .joinToString(" | ") { (path, phases) ->
                val claim = phases["claimed"] ?: -1
                val startup = phases["startup_done"] ?: -1
                val frame = phases["first_frame"] ?: -1
                val suffix = path.substringAfterLast('/')
                "cam$suffix claim=${claim}ms startup=${startup}ms frame=${frame}ms"
            }
        UvcDebugLogger.log(
            context.applicationContext,
            "hik-startup",
            "STARTUP_METRICS build=${BuildConfig.SHUTDOWN_BUILD_STAMP} framing=${HikBulkFramingMode.active.logTag} bus=- phase=session_summary " +
                "elapsed=${total}ms cams=(${camSummaries})",
        )
    }

    fun resetForTests() {
        sessionStartMs = 0L
        perCamPhases.clear()
    }
}
