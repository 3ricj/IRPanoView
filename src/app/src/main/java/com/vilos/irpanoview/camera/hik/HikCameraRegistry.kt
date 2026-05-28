package com.vilos.irpanoview.camera.hik

import android.content.Context
import android.hardware.usb.UsbDevice
import com.vilos.irpanoview.GracefulShutdown
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One [HikCameraController] per bus path — survives Compose recomposition without
 * tearing down USB when [UsbDevice] object identity changes on registry refresh.
 */
object HikCameraRegistry {

    private data class Entry(
        val controller: HikCameraController,
        var refs: Int,
        val deviceId: Int,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val globalShutdown = AtomicBoolean(false)

    fun isGlobalShutdownInFlight(): Boolean = globalShutdown.get()

    /**
     * Exit entry point — blocks new starts; per-camera stop is serialized in [shutdownAll].
     */
    fun beginGlobalShutdown() {
        globalShutdown.set(true)
        HikBlackReferenceCoordinator.cancelPending()
    }

    fun acquire(context: Context, device: UsbDevice): HikCameraController {
        val busPath = device.deviceName ?: error("UsbDevice has no deviceName")
        if (globalShutdown.get()) {
            entries[busPath]?.controller?.let { return it }
            error("Hik acquire during global shutdown with no entry for $busPath")
        }
        val appContext = context.applicationContext
        return entries.compute(busPath) { _, existing ->
            when {
                existing == null -> Entry(
                    HikCameraController(appContext, device),
                    1,
                    device.deviceId,
                )
                existing.refs <= 0 -> {
                    existing.copy(refs = 1, deviceId = device.deviceId)
                }
                existing.deviceId != device.deviceId -> {
                    if (globalShutdown.get()) {
                        existing
                    } else {
                        existing.controller.referenceLeave()
                        Entry(
                            HikCameraController(appContext, device),
                            existing.refs + 1,
                            device.deviceId,
                        )
                    }
                }
                else -> existing.copy(refs = existing.refs + 1)
            }
        }!!.controller.also {
            HikBlackReferenceCoordinator.setLogContext(appContext)
            syncExpectedFromRegistry()
            if (!it.isWorkerRunning()) {
                it.start()
            }
        }
    }

    /**
     * Compose ref drop — reference leave when last ref; full USB close only on detach / stale removal.
     */
    fun release(busPath: String, mode: HikShutdownMode = HikShutdownMode.SoftPause) {
        if (globalShutdown.get() || GracefulShutdown.isPauseInFlight()) return
        entries.computeIfPresent(busPath) { _, entry ->
            val next = entry.refs - 1
            if (next <= 0) {
                when (mode) {
                    HikShutdownMode.SoftPause -> entry.controller.referenceLeave()
                    HikShutdownMode.GracefulExit -> entry.controller.shutdown(HikShutdownMode.GracefulExit)
                    HikShutdownMode.UsbDetached -> entry.controller.shutdown(HikShutdownMode.UsbDetached)
                }
                if (mode == HikShutdownMode.SoftPause) {
                    entry.copy(refs = 0)
                } else {
                    HikBlackReferenceCoordinator.onReleased(busPath)
                    null
                }
            } else {
                entry.copy(refs = next)
            }
        }
        syncExpectedFromRegistry()
    }

    fun requestBlackReference(busPath: String) {
        if (HikPreviewSettings.BLACK_REFERENCE_DESCOPED) return
        entries[busPath]?.controller?.requestBlackReference()
    }

    fun requestBlackReferenceAll() {
        entries.keys.forEach { requestBlackReference(it) }
    }

    fun applyIrConfigAll() {
        entries.keys.forEach { busPath ->
            entries[busPath]?.controller?.requestApplyIrConfig()
        }
    }

    fun activeBusPaths(): Set<String> = entries.keys.toSet()

    fun resetTemporalAveragers() {
        entries.values.forEach { it.controller.resetTemporalAverageHistory() }
    }

    private fun syncExpectedFromRegistry() {
        HikBlackReferenceCoordinator.syncExpected(activeBusPaths())
    }

    fun releaseAbsentFrom(
        activeBusPaths: Set<String>,
        logContext: Context? = null,
        detachedBusPaths: Set<String> = emptySet(),
    ) {
        if (globalShutdown.get()) return
        val stale = entries.keys.filter { it !in activeBusPaths }
        if (stale.isEmpty()) return
        for (path in stale) {
            val mode = if (path in detachedBusPaths) {
                HikShutdownMode.UsbDetached
            } else {
                HikShutdownMode.GracefulExit
            }
            entries.remove(path)?.let { entry ->
                logContext?.let { ctx ->
                    UvcDebugLogger.log(ctx, path, "Hik registry: release absent mode=$mode")
                }
                entry.controller.shutdown(mode)
                HikBlackReferenceCoordinator.onReleased(path)
            }
        }
        syncExpectedFromRegistry()
    }

    /**
     * Reference leave (doc 16 Scenario A) — serial cancel+join per cam; keep fd + controllers.
     */
    fun pauseAll(logContext: Context? = null, reason: String = "reference pause"): Int {
        HikBlackReferenceCoordinator.cancelPending()
        HikUsbOpenGate.waitForIdle(OPEN_GATE_DRAIN_MS)
        val actions = HikShutdownExperiment.referenceSoftPause()
        val snapshot = entries.entries.toList().sortedBy { (busPath, _) -> busPath }
        if (snapshot.isEmpty()) return 0

        logContext?.let { ctx ->
            UvcDebugLogger.log(ctx, "hik-lifecycle", "REFERENCE_PAUSE stop all workers (${snapshot.size} cams)")
        }
        for ((_, entry) in snapshot) {
            entry.controller.requestStop()
        }
        for ((busPath, entry) in snapshot) {
            val stopped = entry.controller.awaitWorkerStop(WORKER_DRAIN_MS)
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, busPath, "REFERENCE_PAUSE worker stopped=$stopped")
            }
        }

        for ((busPath, entry) in snapshot) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, busPath, "Hik registry: $reason referencePause serial")
            }
            entry.controller.referencePause(actions)
            entries[busPath] = entry.copy(refs = 0)
        }
        if (snapshot.isNotEmpty()) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, "hik-lifecycle", "REFERENCE_PAUSE hub settle ${REFERENCE_HUB_SETTLE_MS}ms")
            }
            Thread.sleep(REFERENCE_HUB_SETTLE_MS)
        }
        syncExpectedFromRegistry()
        return snapshot.size
    }

    /**
     * Full Exit — one camera fully quiet before the next starts stop (serial hub order).
     */
    fun shutdownAll(logContext: Context? = null, reason: String = "shutdown"): Int {
        if (!globalShutdown.get()) {
            beginGlobalShutdown()
        }
        HikUsbOpenGate.waitForIdle(OPEN_GATE_DRAIN_MS)
        val recipe = HikShutdownExperiment.activeRecipe
        val stopActions = HikShutdownExperiment.stopChannelActionsForExit()
        val snapshot = entries.entries.toList().sortedBy { (busPath, _) -> busPath }
        if (snapshot.isEmpty()) return 0
        entries.clear()

        for ((busPath, entry) in snapshot) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, busPath, "Hik registry: $reason stopChannel serial")
            }
            val quiet = entry.controller.stopChannelAndRelease(stopActions)
            logContext?.let { ctx ->
                UvcDebugLogger.log(
                    ctx,
                    busPath,
                    "Hik registry: $reason stopChannel quiet=$quiet recipe=${recipe.id}",
                )
            }
        }

        val closeAfterStop = recipe != HikShutdownExperiment.Recipe.E6
        if (closeAfterStop) {
            logContext?.let { ctx ->
                UvcDebugLogger.log(ctx, "hik-lifecycle", "SHUTDOWN_STEP hub settle ${HUB_SETTLE_MS}ms")
            }
            Thread.sleep(HUB_SETTLE_MS)
            for ((busPath, entry) in snapshot) {
                logContext?.let { ctx ->
                    UvcDebugLogger.log(ctx, busPath, "Hik registry: $reason closeHandle")
                }
                entry.controller.closeUsbHandleAndFinalize()
            }
            if (HikProtocolSupport.USE_NATIVE_LIBUSB) {
                logContext?.let { ctx ->
                    UvcDebugLogger.log(
                        ctx,
                        "hik-lifecycle",
                        "SHUTDOWN_STEP post-close settle ${POST_CLOSE_SETTLE_LIBUSB_MS}ms",
                    )
                }
                Thread.sleep(POST_CLOSE_SETTLE_LIBUSB_MS)
            }
        } else {
            for ((_, entry) in snapshot) {
                entry.controller.finalizeAfterStopChannel()
            }
        }

        snapshot.forEach { (busPath, _) ->
            HikBlackReferenceCoordinator.onReleased(busPath)
        }
        syncExpectedFromRegistry()
        return snapshot.size
    }

    fun resetForTests() {
        globalShutdown.set(false)
        entries.values.forEach { it.controller.shutdown(HikShutdownMode.GracefulExit) }
        entries.clear()
        HikBlackReferenceCoordinator.resetForTests()
    }

    private const val OPEN_GATE_DRAIN_MS = 500L
    private const val WORKER_DRAIN_MS = 1_500L
    /** Doc 06 minimum after StopChannel; reference re-bind is slower but gate opens here. */
    private const val REFERENCE_HUB_SETTLE_MS = 500L
    private const val HUB_SETTLE_MS = 150L
    private const val POST_CLOSE_SETTLE_LIBUSB_MS = 300L
}
