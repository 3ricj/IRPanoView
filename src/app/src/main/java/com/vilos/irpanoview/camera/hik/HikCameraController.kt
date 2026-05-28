package com.vilos.irpanoview.camera.hik

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.vilos.irpanoview.GracefulShutdown
import com.vilos.irpanoview.camera.ThermalDisplaySettings
import com.vilos.irpanoview.camera.ThermalLevelRegistry
import com.vilos.irpanoview.model.CameraIdentity
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.util.AgentDebugLog
import com.vilos.irpanoview.util.PreviewSnapshotLogger
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * MasterThermoDocs Hik protocol camera controller: bind, init, UVC wire decode.
 *
 * One instance per [UsbDevice] (bus path identity). Does not use Infisense IRCMD stack.
 */
class HikCameraController(
    context: Context,
    private val device: UsbDevice,
) {
    data class UiFramePacket(
        val bitmap: Bitmap,
        val frameSeq: Long,
        val frameFingerprint: Int,
        val publishedAtMs: Long,
    )

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = device.deviceName ?: "hik"

    private var started = false
    private var worker: Thread? = null
    private val stopRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    private var usb: HikUsbLink? = null
    /** Survives worker clearing [usb] — used by Exit to close the Android handle. */
    @Volatile private var activeConnection: HikUsbLink? = null
    private var session: HikSession? = null

    private var palette: ThermalColorPalette = ThermalColorPalette.default()
    private var pixels = IntArray(HikTherm.GRID_WIDTH * HikTherm.GRID_HEIGHT)
    private var outputBitmap: Bitmap? = null

    private var frameIndex = 0
    private var loggedFirstFrame = false
    private var streamRank = 0
    private var loggedStartup = false
    private var lastDiagMs = 0L
    private var lastFpsLogMs = 0L
    private var framesSinceFpsLog = 0
    private var framePublishSeq = 0L
    private var lastPublishFlowLogMs = 0L
    private var lastUiPostFlowLogMs = 0L
    private var lastGateLogMs = 0L
    private var gateNullFrameCount = 0
    private var gatePartialTimeoutCount = 0
    private var gateStreamFailResetCount = 0
    private var gateRawWireOnlyCount = 0
    private var gateBitmapRecycledCount = 0
    private var lastSnapshotMs = 0L
    private var streaming = false
    @Volatile private var workerStartedAtMs = 0L
    @Volatile private var lastStatusMessage: String? = "Hik: opening USB…"
    @Volatile private var deviceSerial: String? = null
    private val uiPostPending = AtomicBoolean(false)
    private val blackReferenceRequested = AtomicBoolean(false)
    private val irConfigApplyRequested = AtomicBoolean(false)
    private val autoShutterEnabled = AtomicBoolean(false)
    private val temporalAverager = HikTemporalAverager()

    var onFrameBitmap: ((UiFramePacket) -> Unit)? = null
    var onStatus: ((String?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val deviceId: Int get() = device.deviceId

    fun setPalette(p: ThermalColorPalette) {
        palette = p
    }

    fun resetTemporalAverageHistory() {
        temporalAverager.reset()
    }

    /** Re-push overlay state after Compose rewires [onStatus] without restarting the worker. */
    fun syncStatusToListener() {
        val msg = when {
            HikUsbStackRecovery.isFaulted() -> faultStatusMessage()
            streaming -> null
            else -> lastStatusMessage
        }
        mainHandler.post { onStatus?.invoke(msg) }
    }

    /** Queue manual NUC on the camera worker thread (safe while bulk streaming). */
    fun requestBlackReference() {
        blackReferenceRequested.set(true)
    }

    /** Re-push 0x7EF scene params from [HikIrConfigSettings] on the worker thread. */
    fun requestApplyIrConfig() {
        irConfigApplyRequested.set(true)
    }

    /** True while the dedicated worker thread is running a USB session. */
    fun isWorkerRunning(): Boolean = worker?.isAlive == true

    /** Worker alive but no stream yet for too long (openDevice wedge after process kill). */
    fun isStartupStalled(): Boolean {
        if (released.get() ||
            stopRequested.get() ||
            HikCameraRegistry.isGlobalShutdownInFlight() ||
            !started ||
            streaming ||
            HikUsbOpenGate.isOpenLocked()
        ) {
            return false
        }
        val w = worker ?: return false
        if (!w.isAlive) return false
        return System.currentTimeMillis() - workerStartedAtMs > STARTUP_STALL_MS
    }

    /** Tear down a wedged worker so [HikUsbPreview] watchdog can start fresh. */
    fun forceRestartWorker() {
        if (released.get() || stopRequested.get() || HikCameraRegistry.isGlobalShutdownInFlight()) return
        if (HikUsbStackRecovery.isFaulted()) {
            publishStatus(faultStatusMessage())
            return
        }
        if (HikUsbStackRecovery.isInflight(tag)) {
            UvcDebugLogger.log(appContext, tag, "Hik startup stall: open in flight, skip restart")
            return
        }
        UvcDebugLogger.log(appContext, tag, "Hik startup stall: force restart worker")
        requestStop()
        val w = worker
        HikStreamStop.stopChannel(
            logContext = appContext,
            busPath = tag,
            stopRequested = stopRequested,
            worker = w,
            streaming = { streaming },
            clearStreaming = { streaming = false },
            workerJoinMs = HikShutdownExperiment.SOFT_PAUSE_JOIN_MS,
            cancelBulk = { cancelBulkStep(activeConnection ?: usb, HikShutdownExperiment.referenceSoftPause()) },
            workerJoinRetry = false,
            requireWorkerDead = false,
        )
        val conn = activeConnection ?: usb
        activeConnection = null
        usb = null
        session = null
        worker = null
        if (conn != null) {
            closeUsbHandleSync(conn)
        }
        stopRequested.set(false)
        streaming = false
        started = false
        start()
    }

    private fun faultStatusMessage(): String {
        val detail = HikUsbStackRecovery.faultMessage()
        return if (detail != null) "Hik: $detail" else "Hik: USB fault — power-cycle hub"
    }

    /** Signal bulk worker to exit — safe to call from any thread during global shutdown. */
    fun requestStop() {
        stopRequested.set(true)
        streaming = false
        worker?.interrupt()
    }

    fun start() {
        if (released.get()) return
        if (GracefulShutdown.isPauseInFlight()) return
        if (HikCameraRegistry.isGlobalShutdownInFlight()) return
        if (HikUsbStackRecovery.isFaulted()) {
            publishStatus(faultStatusMessage())
            return
        }
        if (worker?.isAlive == true) {
            syncStatusToListener()
            return
        }
        started = true
        workerStartedAtMs = System.currentTimeMillis()
        stopRequested.set(false)
        HikBulkInstrumentation.reset(tag)
        loggedFirstFrame = false
        loggedStartup = false
        streaming = false
        publishStatus("Hik: opening USB…")
        worker = thread(name = "hik-$tag", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            runWorker()
        }
    }

    /** Reference leave — cancel + join (+ optional disarm for E10D A/B); keep USB fd. */
    fun referencePause(
        actions: HikShutdownExperiment.PostBulkQuietActions =
            HikShutdownExperiment.referenceSoftPause(),
    ) {
        if (released.get()) return
        stopChannelAndRelease(actions)
        stopRequested.set(false)
        started = false
    }

    /** Wait for bulk worker exit during reference pause (all cams stopped before serial native stop). */
    fun awaitWorkerStop(maxMs: Long): Boolean {
        val w = worker ?: return true
        w.interrupt()
        w.join(maxMs)
        if (w.isAlive) {
            UvcDebugLogger.log(appContext, tag, "REFERENCE_PAUSE worker alive after ${maxMs}ms")
            return false
        }
        worker = null
        return true
    }

    fun release() = referenceLeave()

    /** Reference leave — same recipe as Exit [pauseAll] (doc 16 Scenario A). */
    fun referenceLeave() {
        if (released.get() || HikCameraRegistry.isGlobalShutdownInFlight()) return
        referencePause(HikShutdownExperiment.referenceSoftPause())
    }

    /** @deprecated use [referenceLeave] */
    fun softPause() = referenceLeave()

    fun shutdown(mode: HikShutdownMode) {
        when (mode) {
            HikShutdownMode.SoftPause -> softPause()
            HikShutdownMode.GracefulExit -> {
                if (!released.compareAndSet(false, true)) return
                UvcDebugLogger.log(appContext, tag, "Hik USB shutdown: release mode=$mode")
                val actions = HikShutdownExperiment.stopChannelActionsForExit()
                stopChannelAndRelease(actions)
                closeUsbHandleAndFinalize()
            }
            HikShutdownMode.UsbDetached -> {
                if (!released.compareAndSet(false, true)) return
                UvcDebugLogger.log(appContext, tag, "Hik USB shutdown: release mode=$mode")
                shutdownUsbDetached()
                finalizeShutdown()
            }
        }
    }

    /**
     * Reference StopChannel on the calling (shutdown) thread: cancel → join → disarm → release.
     * Does not close the Android USB handle.
     */
    fun stopChannelAndRelease(
        actions: HikShutdownExperiment.PostBulkQuietActions =
            HikShutdownExperiment.stopChannelActions(),
    ): Boolean {
        val recipe = HikShutdownExperiment.activeRecipe
        UvcDebugLogger.log(appContext, tag, "SHUTDOWN_RECIPE=${recipe.id} ${recipe.summary}")
        val w = worker
        val connForCancel = activeConnection ?: usb
        val bulkQuiet = HikStreamStop.stopChannel(
            logContext = appContext,
            busPath = tag,
            stopRequested = stopRequested,
            worker = w,
            streaming = { streaming },
            clearStreaming = { streaming = false },
            workerJoinMs = actions.workerJoinMs,
            cancelBulk = if (actions.cancelBulkFirst) {
                { cancelBulkStep(connForCancel, actions) }
            } else {
                null
            },
            workerJoinRetry = actions.workerJoinRetry,
            requireWorkerDead = actions.requireWorkerDead,
        )
        worker = null
        session = null
        streaming = false
        val conn = activeConnection ?: usb
        if (conn == null) {
            UvcDebugLogger.log(appContext, tag, "SHUTDOWN_RECIPE=${recipe.id} no connection ref")
            return bulkQuiet
        }
        if (!bulkQuiet) {
            UvcDebugLogger.log(
                appContext,
                tag,
                "SHUTDOWN_RECIPE=${recipe.id} bulk not quiet — proceeding (${actions.shutdownMode})",
            )
        }
        applyStopChannelSteps(conn, actions)
        if (actions.closeHandle) {
            val closeStartMs = System.currentTimeMillis()
            closeUsbHandleSync(conn)
            activeConnection = null
            usb = null
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H8",
                location = "HikCameraController.stopChannelAndRelease:closeHandle",
                message = "pause closeHandle",
                data = mapOf(
                    "busPath" to tag,
                    "elapsedMs" to (System.currentTimeMillis() - closeStartMs),
                ),
                runId = "post-fix",
            )
            // #endregion
            UvcDebugLogger.log(
                appContext,
                tag,
                "SHUTDOWN_STEP closeHandle elapsed=${System.currentTimeMillis() - closeStartMs}ms",
            )
        }
        return bulkQuiet
    }

    /** After all cameras StopChannel + hub settle (Exit). */
    fun closeUsbHandleAndFinalize() {
        released.set(true)
        val conn = activeConnection ?: usb
        activeConnection = null
        usb = null
        if (conn != null) {
            closeUsbHandleSync(conn)
        }
        finalizeShutdown()
    }

    /** E8-only shutdown from registry — no closeHandle. */
    fun finalizeAfterStopChannel() {
        released.set(true)
        activeConnection = null
        usb = null
        finalizeShutdown()
    }

    private fun applyStopChannelSteps(
        conn: HikUsbLink,
        actions: HikShutdownExperiment.PostBulkQuietActions,
    ) {
        if (conn.usesNativeLibusb) {
            if (actions.callNativeReferenceStop) {
                val vsIface = (conn as? LibusbHikConnection)?.streamingInterfaceId() ?: 1
                val ifaces = conn.claimedInterfaces.toIntArray()
                val elapsed = conn.stopNativeChannelReference(vsIface, ifaces) ?: 0L
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "SHUTDOWN_STEP ref StopChannel elapsed=${elapsed}ms vsIface=$vsIface " +
                        "ifaces=${ifaces.contentToString()}",
                )
            } else if (actions.libusbFlushBeforeStop) {
                val bulkEp = conn.bulkInEndpoint?.address ?: HikTherm.BULK_EP_DEFAULT
                runCatching {
                    val flush = conn.flushBulkEndpoint(
                        bulkEp,
                        deadlineMs = 300L,
                        emptyStreakLimit = 3,
                        perXferMs = 50,
                    )
                    UvcDebugLogger.log(
                        appContext,
                        tag,
                        "SHUTDOWN_STEP libusb flush xfers=${flush.xfers} bytes=${flush.bytes} elapsed=${flush.elapsedMs}ms",
                    )
                }
            }
            if (actions.callNativeStopChannel) {
                val vsIface = (conn as? LibusbHikConnection)?.streamingInterfaceId() ?: 1
                val elapsed = conn.stopNativeChannel(vsIface) ?: 0L
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "SHUTDOWN_STEP libusb stop elapsed=${elapsed}ms vsIface=$vsIface",
                )
            } else if (!actions.callNativeReferenceStop) {
                conn.resetBulkCarry()
            }
            if (actions.settleMs > 0L) {
                UvcDebugLogger.log(appContext, tag, "SHUTDOWN_STEP settle ${actions.settleMs}ms")
                Thread.sleep(actions.settleMs)
            }
            if (actions.releaseInterfaces) {
                val releaseStartMs = System.currentTimeMillis()
                val claimedBefore = conn.claimedInterfaces.toList()
                UvcDebugLogger.log(appContext, tag, "SHUTDOWN_STEP releaseInterfaces claimed=$claimedBefore")
                runCatching { conn.resetBulkRequest() }
                val ifaces = runCatching {
                    conn.releaseClaimedInterfaces(closeBulk = false)
                }.getOrDefault(emptyList())
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "SHUTDOWN_STEP releaseInterfaces ifaces=$ifaces elapsed=" +
                        "${System.currentTimeMillis() - releaseStartMs}ms",
                )
            }
            return
        }
        if (actions.primeBulk) {
            val bulkEp = HikTherm.BULK_EP_DEFAULT
            val flush = runCatching { conn.flushBulkEndpoint(bulkEp) }.getOrNull()
            if (flush != null) {
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "SHUTDOWN_STEP flushBulk xfers=${flush.xfers} bytes=${flush.bytes} " +
                        "emptyStreak=${flush.emptyStreak} elapsed=${flush.elapsedMs}ms",
                )
            } else {
                UvcDebugLogger.log(appContext, tag, "SHUTDOWN_STEP flushBulk failed")
            }
        }
        if (actions.disarm) {
            val disarmStartMs = System.currentTimeMillis()
            val ok = runCatching {
                HikUvcStream.disarmStream(conn, conn, conn.device)
            }.getOrDefault(false)
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H1-H4",
                location = "HikCameraController.applyStopChannelSteps:disarm",
                message = "disarmStream",
                data = mapOf(
                    "ok" to ok,
                    "busPath" to tag,
                    "claimed" to conn.claimedInterfaces.toString(),
                ),
            )
            // #endregion
            UvcDebugLogger.log(
                appContext,
                tag,
                "SHUTDOWN_STEP disarm ok=$ok elapsed=${System.currentTimeMillis() - disarmStartMs}ms",
            )
        }
        if (actions.settleMs > 0L) {
            UvcDebugLogger.log(appContext, tag, "SHUTDOWN_STEP settle ${actions.settleMs}ms")
            Thread.sleep(actions.settleMs)
        }
        if (actions.releaseInterfaces) {
            val releaseStartMs = System.currentTimeMillis()
            val claimedBefore = conn.claimedInterfaces.toList()
            UvcDebugLogger.log(appContext, tag, "SHUTDOWN_STEP releaseInterfaces claimed=$claimedBefore")
            runCatching { conn.resetBulkRequest() }
            val ifaces = runCatching {
                conn.releaseClaimedInterfaces(closeBulk = false)
            }.getOrDefault(emptyList())
            UvcDebugLogger.log(
                appContext,
                tag,
                "SHUTDOWN_STEP releaseInterfaces ifaces=$ifaces elapsed=" +
                    "${System.currentTimeMillis() - releaseStartMs}ms",
            )
        } else if (actions.cancelBulkFirst) {
            conn.resetBulkCarry()
        }
    }

    /** USB detach — cancel (best effort), skip alt-0 on dead fd, close handle. Order: stop/cancel then close. */
    private fun shutdownUsbDetached() {
        val actions = HikShutdownExperiment.stopChannelActionsForDetach()
        val w = worker
        HikStreamStop.stopChannel(
            logContext = appContext,
            busPath = tag,
            stopRequested = stopRequested,
            worker = w,
            streaming = { streaming },
            clearStreaming = { streaming = false },
            workerJoinMs = actions.workerJoinMs,
            cancelBulk = { cancelBulkStep(activeConnection ?: usb, actions) },
            workerJoinRetry = false,
            requireWorkerDead = false,
        )
        worker = null
        session = null
        streaming = false
        val conn = activeConnection ?: usb
        activeConnection = null
        usb = null
        if (conn != null) {
            closeUsbHandleSync(conn, usbDetached = true)
        }
    }

    private fun closeUsbHandleSync(conn: HikUsbLink, usbDetached: Boolean = false) {
        val ifaces = conn.claimedInterfaces.toList()
        val startMs = System.currentTimeMillis()
        var abandoned = false
        runCatching {
            when {
                usbDetached && conn is LibusbHikConnection -> conn.closeHandleDetached()
                conn is HikUsbConnection -> {
                    abandoned = !conn.closeHandleBounded(HikUsbConnection.CLOSE_HANDLE_TIMEOUT_MS)
                }
                else -> conn.closeHandle()
            }
        }
        UvcDebugLogger.log(
            appContext,
            tag,
            "Hik USB closeHandle: released ifaces=$ifaces elapsed=${System.currentTimeMillis() - startMs}ms " +
                "timeout=${CLOSE_TIMEOUT_MS}ms abandoned=$abandoned",
        )
    }

    private fun finalizeShutdown() {
        outputBitmap?.recycle()
        outputBitmap = null
        ThermalLevelRegistry.unregister(tag)
        HikStreamProbe.reset(tag)
        HikEnhancementObservationPlan.resetCamera(tag)
        temporalAverager.reset()
        started = false
        streaming = false
        lastStatusMessage = null
        autoShutterEnabled.set(false)
    }

    private fun cancelBulkStep(conn: HikUsbLink?, actions: HikShutdownExperiment.PostBulkQuietActions) {
        if (conn == null) return
        val result = conn.cancelBulkIn(
            drainAfterCancel = actions.drainAfterCancel,
            cancelWaitMs = actions.cancelWaitMs,
        )
        UvcDebugLogger.log(
            appContext,
            tag,
            "BULK_CANCEL complete cancel=${result.cancelCalled} drained=${result.drained} " +
                "elapsed=${result.elapsedMs}ms inFlight=${result.inFlightAfter}",
        )
    }

    private fun runWorker() {
        var sessionAttempt = 0
        while (!stopRequested.get() && !released.get() && !HikCameraRegistry.isGlobalShutdownInFlight()) {
            val attempt = sessionAttempt + 1
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik restart_state phase=session_begin attempt=$attempt " +
                    "state=${HikUsbStackRecovery.openStateSummary()}",
            )
            try {
                runStreamingSession(attempt)
                break
            } catch (e: SessionRestartException) {
                if (stopRequested.get() || released.get() || HikCameraRegistry.isGlobalShutdownInFlight()) break
                sessionAttempt++
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "Hik session restart (${e.reason}) attempt=$sessionAttempt",
                )
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "Hik restart_state phase=session_restart reason=${e.reason} nextDelayMs=$SESSION_RESTART_BACKOFF_MS",
                )
                publishStatus("Hik: reconnecting…")
                teardownSession(keepConnection = true)
                if (!sleepWorker(SESSION_RESTART_BACKOFF_MS)) break
            } catch (e: ShutdownException) {
                break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (stopRequested.get() || released.get() || HikCameraRegistry.isGlobalShutdownInFlight()) break
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                UvcDebugLogger.log(appContext, tag, "Hik startup failure: $msg")
                if (msg.contains("wVal=500", ignoreCase = true)) {
                    HikUsbStackRecovery.recordControlPlaneDead(tag)
                }
                val retry = shouldRetryStartup(msg)
                val delayMs = if (retry) startupRetryDelayMs(sessionAttempt + 1, msg) else 0L
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "Hik restart_state phase=startup_fail retry=$retry reason=\"$msg\" " +
                        "nextDelayMs=$delayMs keepConnection=$retry state=${HikUsbStackRecovery.openStateSummary()}",
                )
                teardownSession(keepConnection = retry)
                if (retry) {
                    sessionAttempt++
                    publishStatus(
                        if (HikUsbStackRecovery.isFaulted()) {
                            faultStatusMessage()
                        } else {
                            startupRetryStatusMessage(msg)
                        },
                    )
                    if (!sleepWorker(delayMs)) break
                    continue
                }
                mainHandler.post {
                    onError?.invoke("Hik: $msg")
                    onStatus?.invoke("Hik failed: $msg")
                }
                break
            }
        }
        runCatching { usb?.resetBulkCarry() }
        teardownSession(keepConnection = !released.get())
        started = false
    }

    private fun runStreamingSession(attempt: Int) {
        checkNotShutdown()
        loggedFirstFrame = false
        UvcDebugLogger.log(appContext, tag, "Hik restart_state phase=claim_begin attempt=$attempt")
        publishStatus("Hik: claiming USB…")
        val pausedConn = activeConnection ?: usb
        val resumeAfterPause = pausedConn != null
        HikStartupMetrics.mark(appContext, tag, "open_begin")

        val openResult = HikUsbOpenGate.runOpenExclusive {
            checkNotShutdown()
            val paused = pausedConn
            val opened = when {
                paused != null && paused.claimedInterfaces.isEmpty() -> {
                    UvcDebugLogger.log(appContext, tag, "Hik USB reclaim after soft pause (released ifaces)")
                    paused
                }
                paused != null && paused.claimedInterfaces.isNotEmpty() -> {
                    UvcDebugLogger.log(
                        appContext,
                        tag,
                        "Hik USB resume after soft pause (keep claims — pause should have released)",
                    )
                    // #region agent log
                    AgentDebugLog.log(
                        hypothesisId = "H43",
                        location = "HikCameraController.runStreamingSession:keepClaims",
                        message = "resume without openDevice",
                        data = mapOf(
                            "busPath" to tag,
                            "claimed" to paused.claimedInterfaces.toString(),
                        ),
                        runId = "post-fix",
                    )
                    // #endregion
                    paused
                }
                else -> {
                    paused?.let { runCatching { it.closeHandle() } }
                    HikUsbLinks.open(usbManager, device) { stopRequested.get() || released.get() }
                }
            }
            checkNotShutdown()
            val bulkEp = try {
                opened.claimForHik()
            } catch (e: Exception) {
                runCatching { opened.closeHandle() }
                throw e
            }
            opened.logContext = appContext
            streamRank = resolveStreamRank()
            deviceSerial = resolveDeviceSerial()
            val calDesc = resolveCalibrationDescription()
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik USB claimed ifaces=${opened.claimedInterfaces} bulkEp=${bulkEp.toString(16)} " +
                    "transport=${if (opened.usesNativeLibusb) "libusb" else "android"} rank=$streamRank $calDesc",
            )
            HikStartupMetrics.mark(appContext, tag, "claimed")
            opened to bulkEp
        }

        val conn = openResult.first
        var bulkEp = openResult.second
        UvcDebugLogger.log(appContext, tag, "Hik restart_state phase=claim_done attempt=$attempt bulkEp=${bulkEp.toString(16)}")
        usb = conn
        activeConnection = conn

        val skippedBoundPrep = BoundRelaunchPrep(
            performed = false,
            disarmOk = false,
            releasedIfaces = emptyList(),
            bulkEp = bulkEp,
            flushXfers = 0,
            flushBytes = 0,
            unboundReached = false,
            unboundWaitMs = 0L,
            unboundHsHead = "",
        )
        var boundPrep = skippedBoundPrep
        val startupResult = try {
            runStartupBindPhase(
                conn = conn,
                bulkEp = bulkEp,
                attempt = attempt,
                resumeAfterPause = resumeAfterPause,
                forceColdBind = false,
                boundPrep = skippedBoundPrep,
                bindPath = "warm",
            )
        } catch (warmEx: Exception) {
            val warmMsg = warmEx.message?.takeIf { it.isNotBlank() } ?: warmEx.javaClass.simpleName
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik warm startup failed ($warmMsg) — trying bound relaunch fallback",
            )
            boundPrep = prepareBoundRelaunchReset(conn, bulkEp)
            bulkEp = boundPrep.bulkEp
            if (boundPrep.performed && !boundPrep.unboundReached) {
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "Hik bound relaunch fallback unbound=false hs=${boundPrep.unboundHsHead} — proceeding with cold bind",
                )
            }
            try {
                runStartupBindPhase(
                    conn = conn,
                    bulkEp = bulkEp,
                    attempt = attempt,
                    resumeAfterPause = resumeAfterPause,
                    forceColdBind = true,
                    boundPrep = boundPrep,
                    bindPath = "bound_relaunch_cold",
                )
            } catch (fallbackEx: Exception) {
                val fallbackMsg = fallbackEx.message?.takeIf { it.isNotBlank() }
                    ?: fallbackEx.javaClass.simpleName
                if (boundPrep.performed && !boundPrep.unboundReached) {
                    throw HikProtocolException(
                        "warm startup failed ($warmMsg); bound relaunch fallback failed ($fallbackMsg) " +
                            "(hs=${boundPrep.unboundHsHead}) — replug USB cameras",
                    )
                }
                throw fallbackEx
            }
        }
        conn.resetBulkCarry()
        usb?.resetBulkCarry()
        if (!loggedStartup) {
            loggedStartup = true
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik startup profile=${startupResult.enhancementProfile} " +
                    "enhance=${startupResult.enhancementWireSummary} " +
                    "deviceInfo=${startupResult.deviceInfoBytes}B hwStatus=${startupResult.hardwareStatus} " +
                    "thermHead=${startupResult.thermWireHead} streamArm=${startupResult.streamArm?.detail}",
            )
            HikStartupMetrics.mark(appContext, tag, "startup_done")
        }

        val arm = startupResult.streamArm
        if (arm != null && !arm.videoParamSet) {
            publishStatus("Hik: stream arm failed (${arm.detail})")
        } else {
            publishStatus("Hik: waiting for frames…")
        }

        HikBulkGate.registerStreamingWorker()
        var bulkRegistered = true
        conn.resetBulkCarry()
        conn.primeUvcFrameAssembly()
        HikStartupBulkTrace.begin(appContext, tag)
        if (arm == null || !arm.videoParamSet) {
            runCatching { session?.reArmStream() }
        } else {
            (conn as? HikUsbConnection)?.primeBulkEndpoint(bulkEp)
                ?: conn.flushBulkEndpoint(
                    bulkEp,
                    deadlineMs = 6L * HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS,
                    emptyStreakLimit = 1,
                )
        }
        conn.startNativeBulkIfNeeded(bulkEp)
        UvcDebugLogger.log(appContext, tag, "Hik restart_state phase=stream_loop_begin attempt=$attempt")
        try {
            var awaitingFirstFrame = true
            var streamFailCount = 0
            var partialSinceMs = 0L
            var loggedFirstFrameBulkErr = false
            val firstFrameWait = FirstFrameWaitTracker()
            while (!stopRequested.get() && !released.get()) {
                try {
                    if (shouldAbortBulk()) break
                    maybeRunBlackReference()
                    if (!awaitingFirstFrame) {
                        maybeApplyIrConfig()
                    }

                    if (awaitingFirstFrame) {
                        firstFrameWait.ensureDeadline()
                    }

                    val hikSession = session
                    if (hikSession == null) {
                        UvcDebugLogger.log(appContext, tag, "Hik stream loop exit: session cleared")
                        break
                    }
                    val frame = hikSession.drainFrame(
                        bulkEp,
                        awaitingFirstFrame,
                        shouldAbort = ::shouldAbortBulk,
                    )
                    val wireFrames = drainCompleteWireFrames()
                    wireFrames.lastOrNull()?.let { payload ->
                        scheduleUiFrameFromBitmap(HikRawWireBitmap.toBitmap(payload))
                        partialSinceMs = 0L
                        firstFrameWait.clear()
                        loggedFirstFrameBulkErr = false
                        streamFailCount = 0
                        awaitingFirstFrame = false
                        frameIndex++
                        if (!loggedFirstFrame) {
                            loggedFirstFrame = true
                            HikStartupMetrics.mark(appContext, tag, "first_frame")
                            HikStartupBulkTrace.end(appContext, tag, "first_frame")
                            UvcDebugLogger.log(
                                appContext,
                                tag,
                                "Complete wire frame ${payload.size}B (EOF/FID boundary)",
                            )
                            PreviewSnapshotLogger.onRawBytes(
                                appContext,
                                tag,
                                payload,
                                "wire-complete ${payload.size}B",
                            )
                        }
                        if (!streaming) {
                            onStreamingArmed()
                        }
                    }
                    if (frame == null) {
                        if (shouldAbortBulk()) break
                        if (wireFrames.isNotEmpty()) {
                            gateRawWireOnlyCount++
                            continue
                        }
                        gateNullFrameCount++
                        if (usb?.hasPartialPayload() == true) {
                            if (partialSinceMs == 0L) partialSinceMs = System.currentTimeMillis()
                            val partialLimit = if (awaitingFirstFrame) {
                                HikUvcConstants.bulkFirstFrameTimeoutMs()
                            } else {
                                HikUvcConstants.PARTIAL_FRAME_TIMEOUT_MS
                            }
                            if (System.currentTimeMillis() - partialSinceMs > partialLimit) {
                                gatePartialTimeoutCount++
                                UvcDebugLogger.log(
                                    appContext,
                                    tag,
                                    "Hik frame timeout: partial payload stalled " +
                                        "for>${partialLimit}ms during ${if (awaitingFirstFrame) "first-frame" else "steady-state"}",
                                )
                                usb?.resetBulkCarry("partial_timeout")
                                partialSinceMs = 0L
                                streamFailCount++
                            }
                        } else if (awaitingFirstFrame) {
                            handleFirstFrameWaitMiss(firstFrameWait)
                        }
                        continue
                    }

                    partialSinceMs = 0L
                    firstFrameWait.clear()
                    loggedFirstFrameBulkErr = false
                    streamFailCount = 0
                    awaitingFirstFrame = false
                    val meta = usb?.lastFrameMeta
                    if (meta != null) {
                        HikStreamProbe.maybeObservation(
                            appContext,
                            tag,
                            frame,
                            meta.copy(frameIndex = frameIndex++),
                        )
                    } else {
                        frameIndex++
                    }
                    if (!streaming) {
                        onStreamingArmed()
                    }
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        HikStartupMetrics.mark(appContext, tag, "first_frame")
                        HikStartupBulkTrace.end(appContext, tag, "first_frame")
                        UvcDebugLogger.log(
                            appContext,
                            tag,
                            "First Hik frame bytes=${frame.size} uvc=${meta?.reassembly ?: "?"} " +
                                "pkts=${meta?.uvcPackets ?: 0} centerC=${
                                "%.2f".format(
                                    HikTherm.celsiusAtPixel(
                                        frame,
                                        HikTherm.GRID_WIDTH / 2,
                                        HikTherm.GRID_HEIGHT / 2,
                                        deviceSerial,
                                    ),
                                )
                            }",
                        )
                        PreviewSnapshotLogger.onRawBytes(
                            appContext,
                            tag,
                            frame,
                            "hik-uvc-wire ${HikTherm.GRID_WIDTH}x${HikTherm.GRID_HEIGHT}",
                        )
                    }
                    try {
                        if (!HikPreviewSettings.RAW_WIRE_DISPLAY) {
                            publishFrame(frame)
                        }
                    } catch (e: RuntimeException) {
                        UvcDebugLogger.log(appContext, tag, "Hik publishFrame: ${e.message}")
                        outputBitmap?.recycle()
                        outputBitmap = null
                    }
                } catch (e: HikProtocolException) {
                    if (shouldAbortBulk()) break
                    if (awaitingFirstFrame && usb?.hasPartialPayload() != true) {
                        if (!loggedFirstFrameBulkErr) {
                            loggedFirstFrameBulkErr = true
                            UvcDebugLogger.log(appContext, tag, "Hik bulk wait: ${e.message}")
                            publishStatus("Hik: waiting for frames…")
                        }
                        handleFirstFrameWaitMiss(firstFrameWait)
                        continue
                    }
                    streamFailCount++
                    if (streamFailCount == 1 || streamFailCount % 40 == 0) {
                        UvcDebugLogger.log(appContext, tag, "Hik bulk wait: ${e.message}")
                    }
                    if (streamFailCount == 15) {
                        gateStreamFailResetCount++
                        UvcDebugLogger.log(
                            appContext,
                            tag,
                            "Hik frame timeout: stream fail streak reached $streamFailCount, resetting carry",
                        )
                        usb?.resetBulkCarry("stream_fail_streak")
                    }
                    if (!streaming && streamFailCount == 1) {
                        publishStatus("Hik: waiting for frames…")
                    } else if (streaming && streamFailCount >= STREAM_STALL_RESTART_THRESHOLD) {
                        throw SessionRestartException("stream stalled")
                    }
                    if (!shouldAbortBulk()) {
                        Thread.sleep(10)
                    }
                }
            }
        } finally {
            if (bulkRegistered) {
                HikBulkGate.unregisterStreamingWorker()
                bulkRegistered = false
            }
            if (HikStartupBulkTrace.isActive(tag)) {
                HikStartupBulkTrace.end(appContext, tag, "worker_exit")
            }
            if (shouldAbortBulk()) {
                runCatching { usb?.resetBulkCarry() }
            }
        }
    }

    /**
     * Doc 03 warm path: preamble + ready + initConfig + stream arm (`forceColdBind=false`).
     * Fallback only: [prepareBoundRelaunchReset] then cold bind (`forceColdBind=true`).
     */
    private fun runStartupBindPhase(
        conn: HikUsbLink,
        bulkEp: Int,
        attempt: Int,
        resumeAfterPause: Boolean,
        forceColdBind: Boolean,
        boundPrep: BoundRelaunchPrep,
        bindPath: String,
    ): HikSession.StartupResult {
        UvcDebugLogger.log(
            appContext,
            tag,
            "Hik startup bind path=$bindPath forceColdBind=$forceColdBind resumeAfterPause=$resumeAfterPause " +
                "boundPrep=${boundPrep.summary()}",
        )
        val hikSession = HikSession(conn, conn)
        session = hikSession
        UvcDebugLogger.log(appContext, tag, "Hik restart_state phase=startup_begin attempt=$attempt path=$bindPath")
        publishStatus(
            if (forceColdBind) "Hik: rebinding session…" else "Hik: binding session…",
        )
        AgentDebugLog.log(
            hypothesisId = "H24",
            location = "HikCameraController.runStartupBindPhase",
            message = "runStartup begin",
            data = mapOf(
                "bindPath" to bindPath,
                "resumeAfterPause" to resumeAfterPause,
                "forceColdBind" to forceColdBind,
                "boundPrep" to boundPrep.summary(),
                "claimed" to conn.claimedInterfaces.toString(),
                "bulkEp" to bulkEp.toString(16),
                "busPath" to tag,
            ),
            runId = "post-fix",
        )
        return try {
            hikSession.runStartup(
                HikSession.Config(forceColdBind = forceColdBind),
            ).also {
                workerStartedAtMs = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            AgentDebugLog.log(
                hypothesisId = "H3-H5",
                location = "HikCameraController.runStartupBindPhase:fail",
                message = e.message ?: e.javaClass.simpleName,
                data = mapOf(
                    "bindPath" to bindPath,
                    "busPath" to tag,
                    "resumeAfterPause" to resumeAfterPause,
                    "forceColdBind" to forceColdBind,
                ),
                runId = "post-fix",
            )
            session = null
            throw e
        }
    }

    private data class BoundRelaunchPrep(
        val performed: Boolean,
        val disarmOk: Boolean,
        val releasedIfaces: List<Int>,
        val bulkEp: Int,
        val flushXfers: Int,
        val flushBytes: Int,
        val unboundReached: Boolean,
        val unboundWaitMs: Long,
        val unboundHsHead: String,
    ) {
        fun summary(): String =
            if (!performed) {
                "skipped"
            } else {
                "disarm=$disarmOk released=$releasedIfaces flush=$flushXfers/$flushBytes " +
                    "unbound=$unboundReached wait=${unboundWaitMs}ms hs=$unboundHsHead"
            }
    }

    /**
     * Bound relaunch reset: disarm → release ifaces → settle → reclaim → flush → wait for 0x7DE stub.
     * Used only after warm startup failure — disarm → release → reclaim → flush → wait for 0x7DE stub.
     */
    private fun prepareBoundRelaunchReset(conn: HikUsbLink, bulkEp: Int): BoundRelaunchPrep {
        val bound = runCatching { HikUvcProtocol.isDeviceConfigBound(conn) }.getOrDefault(false)
        if (!bound) {
            return BoundRelaunchPrep(
                performed = false,
                disarmOk = false,
                releasedIfaces = emptyList(),
                bulkEp = bulkEp,
                flushXfers = 0,
                flushBytes = 0,
                unboundReached = false,
                unboundWaitMs = 0L,
                unboundHsHead = "",
            )
        }
        val startMs = System.currentTimeMillis()
        val disarmOk = runCatching {
            HikUvcStream.disarmStream(conn, conn, conn.device)
        }.getOrDefault(false)
        conn.resetBulkCarry("bound_relaunch_disarm")
        val releasedIfaces = runCatching {
            conn.releaseClaimedInterfaces(closeBulk = false)
        }.getOrDefault(emptyList())
        if (!sleepWorker(HikUvcConstants.BOUND_RELAUNCH_SETTLE_MS)) {
            throw ShutdownException()
        }
        val reclaimedEp = runCatching { conn.claimForHik() }.getOrElse { e ->
            throw HikProtocolException(
                "bound relaunch reclaim failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        val flush = runCatching {
            conn.flushBulkEndpoint(
                reclaimedEp,
                deadlineMs = HikUvcConstants.BOUND_RELAUNCH_FLUSH_MS,
                emptyStreakLimit = 3,
            )
        }.getOrNull()
        conn.resetBulkCarry("bound_relaunch_flush")
        conn.primeUvcFrameAssembly()
        val unbound = HikUvcProtocol.waitForUnboundStub(conn)
        UvcDebugLogger.log(
            appContext,
            tag,
            "Hik bound relaunch reset disarm=$disarmOk released=$releasedIfaces reclaimEp=" +
                "${reclaimedEp.toString(16)} flush=${flush?.xfers ?: 0}/${flush?.bytes ?: 0} " +
                "unbound=${unbound.reached} wait=${unbound.elapsedMs}ms hs=${unbound.lastHsHead} " +
                "elapsed=${System.currentTimeMillis() - startMs}ms",
        )
        AgentDebugLog.log(
            hypothesisId = "H1-H4",
            location = "HikCameraController.prepareBoundRelaunchReset",
            message = "disarm release reclaim unbound gate",
            data = mapOf(
                "busPath" to tag,
                "disarmOk" to disarmOk,
                "released" to releasedIfaces.toString(),
                "reclaimEp" to reclaimedEp.toString(16),
                "flushXfers" to (flush?.xfers ?: 0),
                "unboundReached" to unbound.reached,
                "unboundWaitMs" to unbound.elapsedMs,
                "unboundHsHead" to unbound.lastHsHead,
            ),
            runId = "post-fix",
        )
        return BoundRelaunchPrep(
            performed = true,
            disarmOk = disarmOk,
            releasedIfaces = releasedIfaces,
            bulkEp = reclaimedEp,
            flushXfers = flush?.xfers ?: 0,
            flushBytes = flush?.bytes ?: 0,
            unboundReached = unbound.reached,
            unboundWaitMs = unbound.elapsedMs,
            unboundHsHead = unbound.lastHsHead,
        )
    }

    private fun teardownSession(keepConnection: Boolean = false) {
        val conn = usb
        usb = null
        session = null
        streaming = false
        autoShutterEnabled.set(false)
        temporalAverager.reset()
        if (HikCameraRegistry.isGlobalShutdownInFlight()) {
            return
        }
        if (keepConnection) {
            return
        }
        activeConnection = null
        runCatching { conn?.closeHandle() }
    }

    private fun shouldAbortBulk(): Boolean =
        stopRequested.get() || released.get() || HikCameraRegistry.isGlobalShutdownInFlight()

    private fun checkNotShutdown() {
        if (shouldAbortBulk()) throw ShutdownException()
    }

    private fun shouldRetryStartup(message: String): Boolean {
        if (HikUsbStackRecovery.isFaulted()) return false
        if (message.contains("replug USB", ignoreCase = true)) return false
        if (message.contains("open cancelled", ignoreCase = true)) return false
        if (message.contains("openDevice already in flight", ignoreCase = true)) return true
        if (message.contains("in flight globally", ignoreCase = true)) return true
        if (message.contains("hub settling", ignoreCase = true)) return true
        if (message.contains("post-stop settling", ignoreCase = true)) return true
        if (message.contains("openDevice returned null", ignoreCase = true)) return true
        if (message.contains("openDevice timeout", ignoreCase = true)) return true
        if (message.contains("claimInterface", ignoreCase = true)) return true
        if (message.contains("InterruptedException", ignoreCase = true)) return true
        return false
    }

    /** User-visible label while backing off — distinguish expected waits from real retries. */
    private fun startupRetryStatusMessage(message: String): String = when {
        message.contains("hub settling", ignoreCase = true) -> "Hik: USB hub settling…"
        message.contains("post-stop settling", ignoreCase = true) -> "Hik: USB settling…"
        message.contains("openDevice already in flight", ignoreCase = true) ||
            message.contains("in flight globally", ignoreCase = true) ||
            message.contains("openDevice returned null", ignoreCase = true) ->
            "Hik: waiting for USB…"
        message.contains("openDevice timeout", ignoreCase = true) -> "Hik: opening USB…"
        else -> "Hik: retrying…"
    }

    private fun startupRetryDelayMs(attempt: Int, message: String): Long {
        val base = if (message.contains("openDevice timeout", ignoreCase = true)) {
            5_000L
        } else if (message.contains("hub settling", ignoreCase = true) ||
            message.contains("post-stop settling", ignoreCase = true) ||
            message.contains("in flight globally", ignoreCase = true)
        ) {
            500L
        } else {
            SESSION_RESTART_BACKOFF_MS
        }
        return (base * attempt.coerceAtMost(4)).coerceAtMost(15_000L)
    }

    /** Sleep on worker thread; returns false if interrupted (caller should break retry loop). */
    private fun sleepWorker(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private class FirstFrameWaitTracker {
        var waitSinceMs: Long = 0L
        var deadlineMs: Long = 0L
        var reArmAttempts: Int = 0

        fun ensureDeadline() {
            if (deadlineMs == 0L) {
                val budget = HikUvcConstants.bulkFirstFrameTimeoutMs()
                waitSinceMs = System.currentTimeMillis()
                deadlineMs = waitSinceMs + budget
            }
        }

        fun clear() {
            waitSinceMs = 0L
            deadlineMs = 0L
            reArmAttempts = 0
        }
    }

    private fun handleFirstFrameWaitMiss(tracker: FirstFrameWaitTracker) {
        if (shouldAbortBulk()) return
        tracker.ensureDeadline()
        val now = System.currentTimeMillis()
        val waited = now - tracker.waitSinceMs
        val earlyReArm = waited >= HikUvcConstants.FIRST_FRAME_EARLY_REARM_MS &&
            tracker.reArmAttempts == 0
        val deadlineHit = now > tracker.deadlineMs
        if (!earlyReArm && !deadlineHit) return

        if (tracker.reArmAttempts < MAX_FIRST_FRAME_REARM) {
            tracker.reArmAttempts++
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik first frame retry: re-arm attempt ${tracker.reArmAttempts} " +
                    "rank=$streamRank early=$earlyReArm waited=${waited}ms",
            )
            HikStartupBulkTrace.onReArm(appContext, tag, tracker.reArmAttempts, waited)
            session?.reArmStream()
            val budget = HikUvcConstants.bulkFirstFrameTimeoutMs()
            tracker.waitSinceMs = System.currentTimeMillis()
            tracker.deadlineMs = tracker.waitSinceMs + budget
            if (!shouldAbortBulk()) {
                Thread.sleep(300)
            }
            return
        }
        throw SessionRestartException("first frame timeout")
    }

    private class SessionRestartException(val reason: String) : Exception(reason)

    private class ShutdownException : Exception("shutdown")

    private fun maybeApplyIrConfig(force: Boolean = false) {
        if (!force && !irConfigApplyRequested.compareAndSet(true, false)) return
        if (force) irConfigApplyRequested.set(false)
        val hikSession = session ?: return
        runCatching {
            hikSession.applyIrConfig()
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik setIrConfig ems=${HikIrConfigSettings.emissivityWire()} " +
                    "dist=${HikIrConfigSettings.distanceWire()} " +
                    "ambient=${"%.1f".format(HikIrConfigSettings.ambientCelsius)}C " +
                    "wire=${HikIrConfigSettings.ambientWire()}",
            )
        }.onFailure { e ->
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik setIrConfig failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun maybeEnableAutoShutter() {
        if (HikPreviewSettings.BLACK_REFERENCE_DESCOPED) {
            autoShutterEnabled.set(true)
            return
        }
        if (!autoShutterEnabled.compareAndSet(false, true)) return
        val hikSession = session ?: return
        runCatching {
            hikSession.setAutoShutter(true)
            UvcDebugLogger.log(appContext, tag, "Hik auto-shutter enabled (0x838/0x2001)")
        }.onFailure { e ->
            autoShutterEnabled.set(false)
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik auto-shutter enable failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun maybeRunBlackReference() {
        if (HikPreviewSettings.BLACK_REFERENCE_DESCOPED) {
            blackReferenceRequested.set(false)
            return
        }
        if (shouldAbortBulk() || GracefulShutdown.isPauseInFlight()) return
        if (!blackReferenceRequested.compareAndSet(true, false)) return
        val hikSession = session ?: return
        runCatching {
            if (!autoShutterEnabled.get()) {
                maybeEnableAutoShutter()
            }
            hikSession.manualShutter()
            temporalAverager.reset()
            UvcDebugLogger.log(appContext, tag, "Hik manual black reference (0x7E9) OK")
        }.onFailure { e ->
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik manual black reference failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun publishFrame(frame: ByteArray) {
        val now = System.currentTimeMillis()
        val displayFrame = if (HikPreviewSettings.temporalAverageFrames <= 1) {
            frame
        } else {
            temporalAverager.blend(frame, HikPreviewSettings.temporalAverageFrames)
        }

        val cx = HikTherm.GRID_WIDTH / 2
        val cy = HikTherm.GRID_HEIGHT / 2
        val centerRaw = HikTherm.rawU16AtPixel(displayFrame, cx, cy)
        val centerRawVisible = runCatching { HikTherm.visibleRawU16AtPixel(displayFrame, cx, cy) }.getOrDefault(-1)
        val centerRawLegacy121e = runCatching { HikTherm.rawU16FlatAtOffset(displayFrame, 0x121e, cx, cy) }.getOrDefault(-1)
        val centerRawLegacy1220 = runCatching { HikTherm.rawU16FlatAtOffset(displayFrame, 0x1220, cx, cy) }.getOrDefault(-1)
        val centerCNoCal = HikTherm.celsiusFromRawU16(centerRaw, serial = null)
        val wireLooksRadio = HikTherm.looksLikeRadiometricWire(displayFrame)
        val frameSeq = ++framePublishSeq
        val frameFingerprint = computeFrameFingerprint(displayFrame, centerRaw)
        HikDisplayTelemetryRegistry.reportPublish(tag, frameSeq, frameFingerprint, now)
        if (now - lastPublishFlowLogMs >= 2_000L) {
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik publish_seq=$frameSeq hash=${frameFingerprintHex(frameFingerprint)}",
            )
            lastPublishFlowLogMs = now
        }
        // #region agent log
        if (frameIndex <= 3) {
            com.vilos.irpanoview.util.AgentDebugLog.log(
                hypothesisId = "B",
                location = "HikCameraController.publishFrame",
                message = "wire decode",
                data = mapOf(
                    "frameIndex" to frameIndex,
                    "frameBytes" to displayFrame.size,
                    "tuning" to HikGridDecodeTuning.effectiveHex(),
                    "centerRaw" to centerRaw,
                    "centerRawVisible" to centerRawVisible,
                    "centerRawLegacy121e" to centerRawLegacy121e,
                    "centerRawLegacy1220" to centerRawLegacy1220,
                    "wireLooksRadio" to wireLooksRadio,
                ),
            )
        }
        // #endregion
        val dynamicCompSnapshot = if (HikPreviewSettings.dynamicCompEnabled) {
            HikDynamicTemperatureCompensation.updateFromFrame(
                serial = deviceSerial,
                frame = displayFrame,
                overlapColumnsN = HikPreviewSettings.dynamicCompOverlapColumns,
                centerBandRatio = HikPreviewSettings.dynamicCompCenterBandRatio,
            )
        } else {
            null
        }
        val dynamicOffsetC = dynamicCompSnapshot?.dynamicOffsetC ?: 0.0
        HikDisplayTelemetryRegistry.reportDynamicComp(
            busPath = tag,
            offsetC = dynamicOffsetC,
            enabled = HikPreviewSettings.dynamicCompEnabled,
            nowMs = now,
        )
        val centerC = HikTherm.celsiusFromRawU16(centerRaw, deviceSerial) + dynamicOffsetC

        framesSinceFpsLog++
        if (now - lastFpsLogMs >= 5_000) {
            val elapsed = (now - lastFpsLogMs).coerceAtLeast(1)
            val fps = framesSinceFpsLog * 1000.0 / elapsed
            UvcDebugLogger.log(appContext, tag, "Hik ui fps=${"%.1f".format(fps)}")
            lastFpsLogMs = now
            framesSinceFpsLog = 0
        }

        val decodeStride = if (HikBulkGate.useParallelBulk()) 8 else 1
        val scan = HikFrameDecoder.scanCelsiusWindow(
            frame = displayFrame,
            policy = ThermalDisplaySettings.hikDynamicWindowPolicy(),
            serial = deviceSerial,
            additionalOffsetC = dynamicOffsetC,
            sampleStride = decodeStride,
        )
        ThermalLevelRegistry.reportWindow(tag, scan.windowMinC, scan.windowMaxC)
        val displayWindow = ThermalLevelRegistry.displayWindowFor(tag)

        HikFrameDecoder.decodeThermalPreview(
            frame = displayFrame,
            palette = if (HikEnhancementObservationPlan.ENABLED) {
                HikEnhancementObservationPlan.CAPTURE_PALETTE
            } else {
                palette
            },
            windowMinC = displayWindow.minCelsius,
            windowMaxC = displayWindow.maxCelsius,
            outPixels = pixels,
            serial = deviceSerial,
            additionalOffsetC = dynamicOffsetC,
        )

        val bmp = outputBitmap?.takeIf {
            it.width == HikTherm.GRID_WIDTH && it.height == HikTherm.GRID_HEIGHT && !it.isRecycled
        } ?: Bitmap.createBitmap(HikTherm.GRID_WIDTH, HikTherm.GRID_HEIGHT, Bitmap.Config.ARGB_8888).also {
            outputBitmap = it
        }
        if (bmp.isRecycled) {
            gateBitmapRecycledCount++
            maybeLogGateInventory(now)
            return
        }
        bmp.setPixels(pixels, 0, HikTherm.GRID_WIDTH, 0, 0, HikTherm.GRID_WIDTH, HikTherm.GRID_HEIGHT)

        if (now - lastDiagMs > 5_000) {
            lastDiagMs = now
            val align = usb?.lastFrameMeta
            UvcDebugLogger.log(
                appContext,
                tag,
                "Hik diag centerC=${"%.2f".format(centerC)} " +
                    "centerCNoCal=${"%.2f".format(centerCNoCal)} " +
                    "centerRaw=$centerRaw centerRawVis=$centerRawVisible " +
                    "centerRawLegacy121e=$centerRawLegacy121e centerRawLegacy1220=$centerRawLegacy1220 " +
                    "looksRadio=$wireLooksRadio " +
                    "cal=${HikRadiometricCalibration.describeOffset(deviceSerial)} " +
                    "dynComp=${"%.3f".format(dynamicOffsetC)} " +
                    "localC=${"%.1f".format(scan.windowMinC)}..${"%.1f".format(scan.windowMaxC)} " +
                    "displayC=${"%.1f".format(displayWindow.minCelsius)}..${"%.1f".format(displayWindow.maxCelsius)} " +
                    "samples=${scan.sampleCount}/${HikTherm.GRID_SAMPLES} " +
                    "uvc=${align?.reassembly ?: "?"} pkts=${align?.uvcPackets ?: 0}",
            )
            if (dynamicCompSnapshot != null) {
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "Hik dyn_comp N=${HikPreviewSettings.dynamicCompOverlapColumns} " +
                        "band=${"%.2f".format(HikPreviewSettings.dynamicCompCenterBandRatio)} " +
                        "samples=${dynamicCompSnapshot.sampleCount} " +
                        "d12=${dynamicCompSnapshot.pairDelta12C?.let { "%.3f".format(it) } ?: "na"} " +
                        "d23=${dynamicCompSnapshot.pairDelta23C?.let { "%.3f".format(it) } ?: "na"} " +
                        "d34=${dynamicCompSnapshot.pairDelta34C?.let { "%.3f".format(it) } ?: "na"} " +
                        "offset=${"%.3f".format(dynamicCompSnapshot.dynamicOffsetC)}",
                )
            }
        }

        if (frameIndex <= 2 || now - lastSnapshotMs >= 30_000) {
            lastSnapshotMs = now
            PreviewSnapshotLogger.onDecodedFrame(
                appContext,
                tag,
                bmp,
                if (frameIndex <= 2) PreviewSnapshotLogger.REASON_FIRST else PreviewSnapshotLogger.REASON_PERIODIC,
            )
        }

        HikEnhancementObservationPlan.maybeObservation(
            context = appContext,
            busPath = tag,
            frame = frame,
            frameIndex = frameIndex,
            bitmap = bmp,
            meta = usb?.lastFrameMeta?.copy(frameIndex = frameIndex),
        )

        maybeLogGateInventory(now)
        scheduleUiFrame(bmp, frameSeq, frameFingerprint, now)
    }

    private fun maybeLogGateInventory(now: Long) {
        if (now - lastGateLogMs < 5_000) return
        if (gateNullFrameCount == 0 &&
            gatePartialTimeoutCount == 0 &&
            gateStreamFailResetCount == 0 &&
            gateRawWireOnlyCount == 0 &&
            gateBitmapRecycledCount == 0
        ) {
            lastGateLogMs = now
            return
        }
        UvcDebugLogger.log(
            appContext,
            tag,
            "Hik gate_inventory nullFrame=$gateNullFrameCount " +
                "partialTimeout=$gatePartialTimeoutCount " +
                "streamFailReset=$gateStreamFailResetCount " +
                "rawWireOnly=$gateRawWireOnlyCount " +
                "bitmapRecycled=$gateBitmapRecycledCount",
        )
        gateNullFrameCount = 0
        gatePartialTimeoutCount = 0
        gateStreamFailResetCount = 0
        gateRawWireOnlyCount = 0
        gateBitmapRecycledCount = 0
        lastGateLogMs = now
    }

    private fun scheduleUiFrame(
        bmp: Bitmap,
        frameSeq: Long,
        frameFingerprint: Int,
        publishedAtMs: Long,
    ) {
        if (uiPostPending.compareAndSet(false, true)) {
            mainHandler.post {
                uiPostPending.set(false)
                val now = System.currentTimeMillis()
                HikDisplayTelemetryRegistry.reportUiPost(tag, frameSeq, frameFingerprint, now)
                if (now - lastUiPostFlowLogMs >= 2_000L) {
                    UvcDebugLogger.log(
                        appContext,
                        tag,
                        "Hik ui_post_seq=$frameSeq hash=${frameFingerprintHex(frameFingerprint)}",
                    )
                    lastUiPostFlowLogMs = now
                }
                onFrameBitmap?.invoke(
                    UiFramePacket(
                        bitmap = bmp,
                        frameSeq = frameSeq,
                        frameFingerprint = frameFingerprint,
                        publishedAtMs = publishedAtMs,
                    ),
                )
            }
        }
    }

    private fun scheduleUiFrameFromBitmap(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        val frameSeq = ++framePublishSeq
        val frameFingerprint = ((bmp.width and 0xFFFF) shl 16) xor (bmp.height and 0xFFFF) xor frameSeq.toInt()
        HikDisplayTelemetryRegistry.reportPublish(tag, frameSeq, frameFingerprint, now)
        scheduleUiFrame(bmp, frameSeq, frameFingerprint, now)
    }

    private fun frameFingerprintHex(fp: Int): String = "%08x".format(fp)

    private fun computeFrameFingerprint(frame: ByteArray, centerRaw: Int): Int {
        var hash = 0x811C9DC5.toInt()
        val len = frame.size
        val step = (len / 64).coerceAtLeast(1)
        var i = 0
        while (i < len) {
            hash = hash xor (frame[i].toInt() and 0xFF)
            hash *= 0x01000193
            i += step
        }
        hash = hash xor centerRaw
        hash *= 0x01000193
        hash = hash xor len
        return hash
    }

    private fun onStreamingArmed() {
        if (streaming) return
        streaming = true
        PreviewSnapshotLogger.markHikStreamingStarted(tag)
        publishStatus(null)
        maybeApplyIrConfig(force = true)
        maybeEnableAutoShutter()
        HikBlackReferenceCoordinator.onStreaming(tag)
    }

    private fun publishStatus(msg: String?) {
        lastStatusMessage = msg
        mainHandler.post { onStatus?.invoke(msg) }
    }

    private fun drainCompleteWireFrames(): List<ByteArray> {
        if (!HikPreviewSettings.RAW_WIRE_DISPLAY) return emptyList()
        val frames = ArrayList<ByteArray>()
        while (true) {
            val payload = usb?.consumeRawWireDisplay() ?: break
            frames.add(payload)
        }
        return frames
    }

    private fun resolveCalibrationDescription(): String =
        HikRadiometricCalibration.describeOffset(deviceSerial)

    private fun resolveDeviceSerial(): String? =
        runCatching {
            if (usbManager.hasPermission(device)) {
                CameraIdentity.resolveSerial(device, true)
            } else {
                null
            }
        }.getOrNull()

    private fun resolveStreamRank(): Int {
        // Use registry bus paths only — reading every device serial before openDevice
        // blocks on the USB stack while another camera holds the open gate.
        val paths = HikMultiCamPolicy.sortedBusPaths(HikCameraRegistry.activeBusPaths().toList())
        val rank = HikMultiCamPolicy.streamRank(tag, paths)
        return if (rank in 0 until HikMultiCamPolicy.ACTIVE_STREAM_LIMIT) rank else 0
    }

    companion object {
        private const val MAX_FIRST_FRAME_REARM = 2
        private const val STREAM_STALL_RESTART_THRESHOLD = 30
        private const val SESSION_RESTART_BACKOFF_MS = 500L
        private const val STARTUP_STALL_MS = 15_000L
        /** Legacy detach close budget — sync path only. */
        private const val CLOSE_TIMEOUT_MS = 400L
    }
}

