package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.GracefulShutdown
import com.vilos.irpanoview.util.AgentDebugLog
import com.vilos.irpanoview.util.UvcDebugLogger
import kotlin.concurrent.thread

/**
 * Post-stream manual NUC coordinator (descoped while [HikPreviewSettings.BLACK_REFERENCE_DESCOPED]).
 *
 * Intended: after all cameras streaming, wait [HikUvcConstants.BLACK_REFERENCE_DELAY_MS] then
 * fire manual NUC (0x7E9) once per session. Disabled until ack + bulk recovery is implemented.
 */
object HikBlackReferenceCoordinator {

    private val lock = Any()
    private val expectedPaths = linkedSetOf<String>()
    private val streamingPaths = linkedSetOf<String>()
    private var sessionGeneration = 0
    private var firedGeneration = -1
    private var waitThread: Thread? = null
    private var logContext: android.content.Context? = null

    fun setLogContext(context: android.content.Context?) {
        logContext = context?.applicationContext
    }

    fun syncExpected(activeBusPaths: Set<String>) {
        synchronized(lock) {
            val next = activeBusPaths.filter { it.isNotBlank() }.toSet()
            if (next == expectedPaths) return
            expectedPaths.clear()
            expectedPaths.addAll(next)
            streamingPaths.retainAll(expectedPaths)
            bumpGenerationLocked()
            maybeScheduleLocked()
        }
    }

    fun onStreaming(busPath: String) {
        synchronized(lock) {
            if (busPath !in expectedPaths) return
            if (!streamingPaths.add(busPath)) return
            if (streamingPaths.size >= expectedPaths.size) {
                logContext?.let { ctx ->
                    HikStartupMetrics.mark(ctx, busPath = null, phase = "all_streaming")
                }
            }
            maybeScheduleLocked()
        }
    }

    fun onReleased(busPath: String) {
        synchronized(lock) {
            expectedPaths.remove(busPath)
            streamingPaths.remove(busPath)
            bumpGenerationLocked()
        }
    }

    fun triggerNow(busPaths: Collection<String>) {
        if (HikPreviewSettings.BLACK_REFERENCE_DESCOPED) return
        if (GracefulShutdown.isPauseInFlight()) return
        for (path in busPaths) {
            HikCameraRegistry.requestBlackReference(path)
        }
    }

    private fun bumpGenerationLocked() {
        sessionGeneration++
        waitThread?.interrupt()
        waitThread = null
    }

    private fun maybeScheduleLocked() {
        if (HikPreviewSettings.BLACK_REFERENCE_DESCOPED) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, "hik-black-ref", "NUC descoped — host black reference disabled")
            }
            firedGeneration = sessionGeneration
            return
        }
        if (GracefulShutdown.isPauseInFlight()) return
        if (expectedPaths.isEmpty()) return
        if (streamingPaths.size < expectedPaths.size) return
        if (firedGeneration == sessionGeneration) return
        if (shouldSkipAutoBlackReferenceLocked()) return

        val gen = sessionGeneration
        val paths = expectedPaths.toList()
        waitThread?.interrupt()
        waitThread = thread(name = "hik-black-ref", isDaemon = true) {
            try {
                Thread.sleep(HikUvcConstants.BLACK_REFERENCE_DELAY_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            synchronized(lock) {
                if (gen != sessionGeneration) return@synchronized
                if (streamingPaths.size < expectedPaths.size) return@synchronized
                firedGeneration = gen
            }
            logContext?.let { ctx ->
                UvcDebugLogger.log(
                    ctx,
                    "hik-black-ref",
                    "All ${paths.size} cameras streaming — manual NUC after " +
                        "${HikUvcConstants.BLACK_REFERENCE_DELAY_MS}ms: ${paths.joinToString()}",
                )
            }
            triggerNow(paths)
        }
    }

    /** Parallel manualShutter on 4 Android fds wedges hub control plane (logs: wVal=500, openDevice timeout). */
    private fun shouldSkipAutoBlackReferenceLocked(): Boolean {
        if (HikProtocolSupport.USE_NATIVE_LIBUSB || expectedPaths.size <= 1) return false
        logContext?.let { ctx ->
            UvcDebugLogger.log(
                ctx,
                "hik-black-ref",
                "skip auto NUC: Android multi-cam (${expectedPaths.size} paths) — use Settings manual NUC per camera",
            )
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H29",
            location = "HikBlackReferenceCoordinator.maybeScheduleLocked",
            message = "skip auto NUC Android multi-cam",
            data = mapOf(
                "camCount" to expectedPaths.size,
                "paths" to expectedPaths.joinToString(),
            ),
            runId = "post-fix",
        )
        // #endregion
        firedGeneration = sessionGeneration
        return true
    }

    fun cancelPending() {
        synchronized(lock) {
            waitThread?.interrupt()
            waitThread = null
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            expectedPaths.clear()
            streamingPaths.clear()
            sessionGeneration = 0
            firedGeneration = -1
            waitThread?.interrupt()
            waitThread = null
        }
    }
}
