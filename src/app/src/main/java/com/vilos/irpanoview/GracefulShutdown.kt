package com.vilos.irpanoview

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.ComponentActivity
import com.vilos.irpanoview.BuildConfig
import com.vilos.irpanoview.camera.hik.HikBlackReferenceCoordinator
import com.vilos.irpanoview.camera.hik.HikProtocolSupport
import com.vilos.irpanoview.camera.hik.HikUsbLifecycle
import com.vilos.irpanoview.camera.hik.HikUsbStackRecovery
import com.vilos.irpanoview.camera.hik.HikShutdownExperiment
import com.vilos.irpanoview.util.AgentDebugLog
import com.vilos.irpanoview.util.UvcDebugLogger
import kotlin.concurrent.thread

/**
 * Reference leave (doc 16 Scenario A): cancel bulk on all cams, finish activity, **keep process + fd**.
 *
 * Debug builds also register [ACTION] for remote trigger:
 * `adb shell am broadcast -a com.vilos.irpanoview.GRACEFUL_SHUTDOWN -p com.vilos.irpanoview`
 */
object GracefulShutdown {

    const val ACTION = "com.vilos.irpanoview.GRACEFUL_SHUTDOWN"

    @Volatile
    private var mainActivity: ComponentActivity? = null

    @Volatile
    private var pauseInFlight = false

    @Volatile
    private var pauseCompleted = false

    private var adbReceiverRegistered = false

    private val adbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                ACTION -> request(context, "adb broadcast")
                HikShutdownExperiment.ACTION_SET_RECIPE -> {
                    val id = intent.getStringExtra(HikShutdownExperiment.EXTRA_RECIPE) ?: return
                    HikShutdownExperiment.setRecipe(id, context.applicationContext)
                }
            }
        }
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity is MainActivity) mainActivity = activity
                }

                override fun onActivityPaused(activity: Activity) {
                    if (mainActivity === activity) mainActivity = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        if (BuildConfig.DEBUG) {
            registerAdbReceiver(app)
        }
    }

    fun isPauseCompleted(): Boolean = pauseCompleted

    fun isPauseInFlight(): Boolean = pauseInFlight

    /** @deprecated use [isPauseCompleted] */
    fun isShutdownCompleted(): Boolean = pauseCompleted

    /** @deprecated use [isPauseInFlight] */
    fun isShutdownInFlight(): Boolean = pauseInFlight

    /** New [MainActivity] session — allow Exit again (does not clear in-flight pause). */
    fun resetForResume() {
        pauseCompleted = false
    }

    /** Block relaunch until reference pause thread finishes (avoids reclaim during pause). */
    fun waitForReferencePause(maxWaitMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (pauseInFlight && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    private fun registerAdbReceiver(app: Application) {
        if (adbReceiverRegistered) return
        val filter = IntentFilter(ACTION).apply {
            addAction(HikShutdownExperiment.ACTION_SET_RECIPE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(adbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(adbReceiver, filter)
        }
        adbReceiverRegistered = true
    }

    /**
     * Reference leave: finish activity; serialized cancel+join per cam; keep process + fd.
     */
    fun request(context: Context, reason: String) {
        if (pauseInFlight || pauseCompleted) return
        pauseInFlight = true
        HikBlackReferenceCoordinator.cancelPending()
        val appContext = context.applicationContext
        val activity = (context as? ComponentActivity) ?: mainActivity
        UvcDebugLogger.log(
            appContext,
            "hik-lifecycle",
            "GracefulShutdown reference leave: $reason recipe=${HikShutdownExperiment.activeRecipe.id} " +
                "build=${BuildConfig.SHUTDOWN_BUILD_STAMP}",
        )
        activity?.runOnUiThread { activity.finishAndRemoveTask() }

        thread(name = "reference-pause", isDaemon = false) {
            try {
                val count = HikUsbLifecycle.pauseAll(appContext, reason)
                HikUsbStackRecovery.onReferencePauseComplete(appContext)
                pauseCompleted = true
                val handleNote = if (HikShutdownExperiment.referenceSoftPause().closeHandle) {
                    "handle closed"
                } else {
                    "fd kept"
                }
                UvcDebugLogger.log(
                    appContext,
                    "hik-lifecycle",
                    "GracefulShutdown reference leave complete: $count cameras paused ($handleNote)",
                )
                if (!HikShutdownExperiment.referenceSoftPause().closeHandle) {
                    // #region agent log
                    AgentDebugLog.log(
                        hypothesisId = "H43",
                        location = "GracefulShutdown.request:sameProcessResume",
                        message = "E10 keep-fd exit — no killProcess, resume reuses open handles",
                        data = mapOf("camCount" to count),
                        runId = "post-fix",
                    )
                    // #endregion
                    UvcDebugLogger.log(
                        appContext,
                        "hik-lifecycle",
                        "GracefulShutdown exit: same-process resume (fd kept, no openDevice on relaunch)",
                    )
                }
                if (!HikProtocolSupport.USE_NATIVE_LIBUSB &&
                    HikShutdownExperiment.referenceSoftPause().closeHandle
                ) {
                    val settleMs = HikUsbStackRecovery.COLD_RELAUNCH_SETTLE_MS
                    HikUsbStackRecovery.persistColdRelaunchSettle(appContext, settleMs)
                    // #region agent log
                    AgentDebugLog.log(
                        hypothesisId = "H31",
                        location = "GracefulShutdown.request:exitProcess",
                        message = "killProcess after E10 closeHandle pause",
                        data = mapOf(
                            "camCount" to count,
                            "settleMs" to settleMs,
                        ),
                        runId = "post-fix",
                    )
                    AgentDebugLog.log(
                        hypothesisId = "H32",
                        location = "GracefulShutdown.request:persistSettle",
                        message = "persistColdRelaunchSettle before killProcess",
                        data = mapOf(
                            "settleMs" to settleMs,
                            "remainMs" to HikUsbStackRecovery.postStopSettleRemainMs(),
                        ),
                        runId = "post-fix",
                    )
                    // #endregion
                    UvcDebugLogger.log(
                        appContext,
                        "hik-lifecycle",
                        "GracefulShutdown exit: killProcess for cold relaunch (clears wedged openDevice threads)",
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            } catch (e: Exception) {
                UvcDebugLogger.log(appContext, "hik-lifecycle", "GracefulShutdown reference leave error: ${e.message}")
            } finally {
                pauseInFlight = false
            }
        }
    }
}
