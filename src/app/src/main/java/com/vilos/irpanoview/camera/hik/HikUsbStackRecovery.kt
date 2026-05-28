package com.vilos.irpanoview.camera.hik

import android.content.Context
import com.vilos.irpanoview.util.AgentDebugLog
import com.vilos.irpanoview.util.UvcDebugLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks wedged [UsbManager.openDevice] calls and stops retry storms that wedge the
 * host controller / powered hub until a full hub power-cycle.
 *
 * A timed-out open leaves a daemon thread blocked inside openDevice forever; without
 * this guard each watchdog cycle spawns more blocked threads.
 */
object HikUsbStackRecovery {

    private const val OPEN_TIMEOUTS_BEFORE_FAULT = 2
    private const val HUB_SETTLE_MS = 1_500L
    const val POST_STOP_SETTLE_MS = 1_000L
    /** libusb fd wrap needs longer kernel settle before relaunch openDevice. */
    const val POST_STOP_SETTLE_LIBUSB_MS = 300L
    /** Block USB_DEVICE_ATTACHED auto-launch after Exit (survives process kill). */
    private const val USB_ATTACH_BLOCK_MS = 5_000L
    /** Hub settle after E10 closeHandle + killProcess — must survive process restart (logs: 10s still openDevice timeout). */
    const val COLD_RELAUNCH_SETTLE_MS = 15_000L
    /** Extra gate after an abandoned openDevice timeout before retry (in-memory). */
    private const val OPEN_TIMEOUT_RETRY_SETTLE_MS = 5_000L

    private const val PREFS_NAME = "hik_usb_recovery"
    private const val KEY_POST_STOP_SETTLE_UNTIL = "post_stop_settle_until_ms"
    private const val KEY_USB_ATTACH_BLOCK_UNTIL = "usb_attach_block_until_ms"

    private val inFlightByPath = ConcurrentHashMap<String, Thread>()
    private val openTimeoutCount = AtomicInteger(0)
    private val faulted = AtomicBoolean(false)
    @Volatile private var faultDetail: String? = null
    private val hubAttachAtMs = AtomicLong(0L)
    private val postStopSettleUntilMs = AtomicLong(0L)
    private val usbAttachBlockUntilMs = AtomicLong(0L)

    fun isFaulted(): Boolean = faulted.get()

    fun faultMessage(): String? = faultDetail

    fun isInflight(busPath: String): Boolean {
        val t = inFlightByPath[busPath] ?: return false
        return t.isAlive
    }

    fun hasAnyInflight(): Boolean = inFlightByPath.values.any { it.isAlive }

    fun registerInflight(busPath: String, thread: Thread) {
        inFlightByPath[busPath] = thread
    }

    fun clearInflight(busPath: String, thread: Thread) {
        inFlightByPath.remove(busPath, thread)
    }

    /** Load persisted post-Exit settle deadline for relaunch in a new process. */
    fun install(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val until = prefs.getLong(KEY_POST_STOP_SETTLE_UNTIL, 0L)
        if (until > now) {
            postStopSettleUntilMs.set(until)
        } else if (until > 0L) {
            prefs.edit().remove(KEY_POST_STOP_SETTLE_UNTIL).apply()
        }
        val attachBlock = prefs.getLong(KEY_USB_ATTACH_BLOCK_UNTIL, 0L)
        if (attachBlock > now) {
            usbAttachBlockUntilMs.set(attachBlock)
        } else if (attachBlock > 0L) {
            prefs.edit().remove(KEY_USB_ATTACH_BLOCK_UNTIL).apply()
        }
        val remainMs = postStopSettleRemainMs()
        if (until > 0L || attachBlock > 0L) {
            UvcDebugLogger.log(
                context.applicationContext,
                "hik-lifecycle",
                "COLD_RELAUNCH install until=$until attachBlock=$attachBlock remainMs=$remainMs",
            )
        }
        // #region agent log
        if (remainMs > 0L) {
            AgentDebugLog.log(
                hypothesisId = "H33",
                location = "HikUsbStackRecovery.install",
                message = "loaded post-stop settle from prefs",
                data = mapOf(
                    "untilMs" to until,
                    "attachBlockMs" to attachBlock,
                    "remainMs" to remainMs,
                ),
                runId = "post-fix",
            )
        }
        // #endregion
    }

    /** True while Exit cooldown blocks [UsbManager.ACTION_USB_DEVICE_ATTACHED] auto-launch. */
    fun shouldBlockUsbAttachLaunch(): Boolean {
        val remain = usbAttachBlockUntilMs.get() - System.currentTimeMillis()
        return remain > 0L
    }

    fun checkCanOpen(busPath: String) {
        if (faulted.get()) {
            throw HikProtocolException(faultDetail ?: "USB stack fault — power-cycle hub")
        }
        if (hasAnyInflight()) {
            val path = inFlightByPath.entries.firstOrNull { it.value.isAlive }?.key ?: "unknown"
            throw HikProtocolException("openDevice in flight globally ($path)")
        }
        if (isInflight(busPath)) {
            throw HikProtocolException("openDevice already in flight for $busPath")
        }
        val postStopRemain = postStopSettleRemainMs()
        if (postStopRemain > 0L) {
            throw HikProtocolException("post-stop settling (${postStopRemain}ms)")
        }
        val attachAt = hubAttachAtMs.get()
        if (attachAt > 0L) {
            val remain = attachAt + HUB_SETTLE_MS - System.currentTimeMillis()
            if (remain > 0L) {
                throw HikProtocolException("hub settling (${remain}ms)")
            }
        }
    }

    /** One-line snapshot for startup/restart diagnostics. */
    fun openStateSummary(nowMs: Long = System.currentTimeMillis()): String {
        val inFlightPath = inFlightByPath.entries.firstOrNull { it.value.isAlive }?.key
        val attachRemain = run {
            val attachAt = hubAttachAtMs.get()
            if (attachAt <= 0L) 0L else (attachAt + HUB_SETTLE_MS - nowMs).coerceAtLeast(0L)
        }
        val postStopRemain = (postStopSettleUntilMs.get() - nowMs).coerceAtLeast(0L)
        return "faulted=${faulted.get()} inFlight=${inFlightPath ?: "-"} " +
            "timeouts=${openTimeoutCount.get()} attachRemainMs=$attachRemain postStopRemainMs=$postStopRemain"
    }

    fun postStopSettleRemainMs(): Long {
        val remain = postStopSettleUntilMs.get() - System.currentTimeMillis()
        return remain.coerceAtLeast(0L)
    }

    /** Block relaunch UI/workers until persisted post-Exit settle expires (2159: workers raced at settle boundary). */
    fun awaitPostStopSettleBlocking(context: Context) {
        val remain = postStopSettleRemainMs()
        if (remain <= 0L) return
        UvcDebugLogger.log(
            context.applicationContext,
            "hik-lifecycle",
            "COLD_RELAUNCH await settle ${remain}ms",
        )
        Thread.sleep(remain)
    }

    /** After reference leave — brief open gate; no USB attach block (process stays alive). */
    fun onReferencePauseComplete(context: Context) {
        val settleMs = if (
            !HikProtocolSupport.USE_NATIVE_LIBUSB &&
            HikShutdownExperiment.referenceSoftPause().closeHandle
        ) {
            HUB_SETTLE_MS
        } else {
            REFERENCE_PAUSE_SETTLE_MS
        }
        val until = System.currentTimeMillis() + settleMs
        postStopSettleUntilMs.set(until)
        openTimeoutCount.set(0)
        faulted.set(false)
        faultDetail = null
        UvcDebugLogger.log(
            context.applicationContext,
            "hik-lifecycle",
            "REFERENCE_PAUSE settle ${settleMs}ms until=$until",
        )
    }

    /** Doc 06 minimum settle after reference leave (same process — in-memory only). */
    private const val REFERENCE_PAUSE_SETTLE_MS = 500L

    /** After full exit with fd close — block opens until doc 06 minimum settle (survives process kill). */
    fun onGracefulShutdownComplete(context: Context) {
        val now = System.currentTimeMillis()
        val settleMs = if (HikProtocolSupport.USE_NATIVE_LIBUSB) {
            POST_STOP_SETTLE_LIBUSB_MS
        } else {
            POST_STOP_SETTLE_MS
        }
        val until = now + settleMs
        val attachBlock = now + USB_ATTACH_BLOCK_MS
        postStopSettleUntilMs.set(until)
        usbAttachBlockUntilMs.set(attachBlock)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_POST_STOP_SETTLE_UNTIL, until)
            .putLong(KEY_USB_ATTACH_BLOCK_UNTIL, attachBlock)
            .apply()
    }

    /**
     * Persist open/attach gate before [android.os.Process.killProcess] on E10 Android exit.
     * In-memory settle from [onReferencePauseComplete] is lost on process death.
     */
    fun persistColdRelaunchSettle(context: Context, settleMs: Long = COLD_RELAUNCH_SETTLE_MS) {
        val now = System.currentTimeMillis()
        val until = now + settleMs
        val attachBlock = now + USB_ATTACH_BLOCK_MS
        postStopSettleUntilMs.set(until)
        usbAttachBlockUntilMs.set(attachBlock)
        openTimeoutCount.set(0)
        faulted.set(false)
        faultDetail = null
        val committed = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_POST_STOP_SETTLE_UNTIL, until)
            .putLong(KEY_USB_ATTACH_BLOCK_UNTIL, attachBlock)
            .commit()
        UvcDebugLogger.log(
            context.applicationContext,
            "hik-lifecycle",
            "COLD_RELAUNCH settle ${settleMs}ms until=$until attachBlockUntil=$attachBlock commit=$committed",
        )
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H33",
            location = "HikUsbStackRecovery.persistColdRelaunchSettle",
            message = "persist settle before killProcess",
            data = mapOf(
                "settleMs" to settleMs,
                "untilMs" to until,
                "commitOk" to committed,
                "remainMs" to postStopSettleRemainMs(),
            ),
            runId = "post-fix",
        )
        // #endregion
    }

    /** Wait for any prior timed-out open thread to finish instead of spawning another. */
    fun waitForGlobalInflightClear(stopCheck: () -> Boolean) {
        while (hasAnyInflight()) {
            if (stopCheck()) return
            Thread.sleep(250)
        }
    }

    /** Wait for a prior timed-out open thread to finish instead of spawning another. */
    fun waitForInflightClear(busPath: String, stopCheck: () -> Boolean) {
        waitForGlobalInflightClear(stopCheck)
        while (isInflight(busPath)) {
            if (stopCheck()) return
            Thread.sleep(250)
        }
    }

    fun recordOpenTimeout(busPath: String) {
        val extraUntil = System.currentTimeMillis() + OPEN_TIMEOUT_RETRY_SETTLE_MS
        while (true) {
            val cur = postStopSettleUntilMs.get()
            val next = maxOf(cur, extraUntil)
            if (postStopSettleUntilMs.compareAndSet(cur, next)) break
        }
        val n = openTimeoutCount.incrementAndGet()
        if (n >= OPEN_TIMEOUTS_BEFORE_FAULT && faulted.compareAndSet(false, true)) {
            faultDetail =
                "USB open wedged after $n timeouts (last $busPath) — power-cycle the hub, then reopen the app"
        }
    }

    /** Post-exit control plane dead (claim ok, wVal=0x500 fails) — hub power-cycle required. */
    fun recordControlPlaneDead(busPath: String) {
        if (faulted.compareAndSet(false, true)) {
            faultDetail =
                "USB control plane dead after exit ($busPath) — power-cycle the hub, then reopen the app"
        }
    }

    /** native usb.close() did not finish within timeout during Exit. */
    fun recordAbandonedClose(busPath: String) {
        if (faulted.compareAndSet(false, true)) {
            faultDetail =
                "USB close abandoned during exit ($busPath) — power-cycle the hub before relaunch"
        }
    }

    /** All cameras detached — hub unplugged from tablet. */
    fun onHubFullyDetached() {
        hubAttachAtMs.set(0L)
        openTimeoutCount.set(0)
        faulted.set(false)
        faultDetail = null
        postStopSettleUntilMs.set(0L)
        usbAttachBlockUntilMs.set(0L)
        // inFlight threads may still be blocked in native openDevice until they return.
    }

    /** First camera(s) seen after detach — debounce opens while hub enumerates. */
    fun onHubAttached() {
        hubAttachAtMs.set(System.currentTimeMillis())
        openTimeoutCount.set(0)
        faulted.set(false)
        faultDetail = null
    }

    /** Cold start with hub already connected — refresh() sees devices before ATTACHED broadcast. */
    fun notifyHubDevicesPresent(context: Context) {
        if (hubAttachAtMs.get() == 0L) {
            onHubAttached()
            HikStartupMetrics.mark(context, busPath = null, phase = "hub_devices_present")
        }
    }

    fun resetForTests() {
        inFlightByPath.clear()
        openTimeoutCount.set(0)
        faulted.set(false)
        faultDetail = null
        hubAttachAtMs.set(0L)
        postStopSettleUntilMs.set(0L)
        usbAttachBlockUntilMs.set(0L)
    }

    internal fun setPostStopSettleUntilForTests(untilMs: Long) {
        postStopSettleUntilMs.set(untilMs)
    }
}
