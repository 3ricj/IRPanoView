package com.vilos.irpanoview.camera.hik

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vilos.irpanoview.BuildConfig
import com.vilos.irpanoview.util.UvcDebugLogger

/**
 * Debug Exit recipes — vary steps after bulk StopChannel quiet.
 *
 * Default **E10D** = reference leave + disarm + release, keep fd (doc 16 bound-relaunch fix).
 * libusb stop (alt-0/release) opt-in only when [HikProtocolSupport.USE_NATIVE_LIBUSB].
 */
object HikShutdownExperiment {

    const val ACTION_SET_RECIPE = "com.vilos.irpanoview.SET_SHUTDOWN_RECIPE"
    const val EXTRA_RECIPE = "recipe"
    private const val PREFS = "hik_shutdown_experiment"
    private const val KEY_RECIPE = "recipe_id"
    private const val KEY_PREFS_VER = "prefs_ver"
    /** Bump to reset persisted recipe (debug builds). */
    private const val PREFS_VER = 8

    /** Soft-pause worker join — reference ~10 ms single-cam; 400 ms budget for 4-cam hub. */
    const val SOFT_PAUSE_JOIN_MS = 400L

    enum class Recipe(val id: String, val summary: String) {
        E1("E1", "bulk quiet → closeHandle"),
        E2("E2", "bulk quiet → disarm → closeHandle"),
        E4("E4", "bulk quiet → flush → disarm → closeHandle"),
        E6("E6", "bulk quiet → no closeHandle"),
        E7("E7", "bulk quiet → disarm → 500ms → closeHandle"),
        E8("E8", "legacy A/B: 3s join → flush → disarm → release, no close"),
        E9("E9", "legacy Exit: E8 per cam serial → hub settle → closeHandle"),
        E10("E10", "ref leave: cancel+join, keep fd (doc 16)"),
        /** A/B fallback if E10 wedges after claim fix — alt-0 on pause (doc 16 disarm experiment). */
        E10D("E10D", "ref leave: cancel+join+disarm, keep fd"),
    }

    var activeRecipeState by mutableStateOf(Recipe.E10D)
        private set

    val activeRecipe: Recipe get() = activeRecipeState

    private var appContext: Context? = null
    private var recipeReceiverRegistered = false

    private val recipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_SET_RECIPE) return
            val id = intent.getStringExtra(EXTRA_RECIPE) ?: return
            setRecipe(id, context)
        }
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        registerRecipeReceiver(context.applicationContext)
        load(context)
        UvcDebugLogger.log(
            context.applicationContext,
            "hik-lifecycle",
            "SHUTDOWN_BUILD=${BuildConfig.SHUTDOWN_BUILD_STAMP} recipe=${activeRecipe.id}",
        )
    }

    private fun registerRecipeReceiver(context: Context) {
        if (recipeReceiverRegistered) return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }
        ContextCompat.registerReceiver(context, recipeReceiver, IntentFilter(ACTION_SET_RECIPE), flags)
        recipeReceiverRegistered = true
    }

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_PREFS_VER, 0) < PREFS_VER) {
            prefs.edit()
                .putInt(KEY_PREFS_VER, PREFS_VER)
                .putString(KEY_RECIPE, Recipe.E10D.id)
                .apply()
        }
        val id = prefs.getString(KEY_RECIPE, Recipe.E10D.id) ?: Recipe.E10D.id
        applyRecipeId(id, context, logChange = false)
    }

    fun applyLaunchIntent(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_RECIPE) ?: return
        setRecipe(id, context)
    }

    fun setRecipe(id: String, logContext: Context? = null): Recipe? {
        val recipe = Recipe.entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return null
        val ctx = logContext?.applicationContext ?: appContext
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_RECIPE, recipe.id)
            ?.apply()
        applyRecipeId(recipe.id, logContext ?: ctx, logChange = true)
        return recipe
    }

    private fun applyRecipeId(id: String, logContext: Context?, logChange: Boolean) {
        Recipe.entries.firstOrNull { it.id == id }?.let { recipe ->
            activeRecipeState = recipe
            if (logChange) {
                logContext?.let { ctx ->
                    UvcDebugLogger.log(ctx, "hik-lifecycle", "SHUTDOWN_RECIPE=${recipe.id} ${recipe.summary}")
                }
            }
        }
    }

    data class PostBulkQuietActions(
        val shutdownMode: HikShutdownMode = HikShutdownMode.SoftPause,
        val disarm: Boolean = false,
        val primeBulk: Boolean = false,
        val settleMs: Long = 0L,
        val closeHandle: Boolean = true,
        val releaseInterfaces: Boolean = false,
        val workerJoinMs: Long = HikStreamStop.STOP_CHANNEL_JOIN_MS,
        val requireWorkerDead: Boolean = true,
        val cancelBulkFirst: Boolean = true,
        /** Kotlin post-cancel poll loop in [LibusbHikConnection.cancelBulkIn]. */
        val drainAfterCancel: Boolean = true,
        /** Native cancel join cap (ms). */
        val cancelWaitMs: Int = CANCEL_WAIT_FULL_MS,
        /** 300 ms libusb flush before native stop — off for E10 soft pause. */
        val libusbFlushBeforeStop: Boolean = false,
        /** Second cancel via native StopChannel — off for E10 (single cancel path). */
        val callNativeStopChannel: Boolean = false,
        /** Native reference StopChannel — cancel + alt-0 + release (libuvc uvc_release_if). */
        val callNativeReferenceStop: Boolean = false,
        val workerJoinRetry: Boolean = true,
    )

    /**
     * Reference leave — cancel + join; keep USB fd (doc 16 Scenario A).
     * 2206 logs: closeHandle + killProcess → openDevice timeout on relaunch; same-process resume avoids open.
     * E10D A/B adds disarm + release without closeHandle.
     */
    fun referenceSoftPause(mode: HikShutdownMode = HikShutdownMode.SoftPause): PostBulkQuietActions =
        PostBulkQuietActions(
            shutdownMode = mode,
            disarm = false,
            releaseInterfaces = false,
            closeHandle = false,
            workerJoinMs = SOFT_PAUSE_JOIN_MS,
            requireWorkerDead = false,
            cancelBulkFirst = true,
            drainAfterCancel = false,
            cancelWaitMs = CANCEL_WAIT_SOFT_MS,
            libusbFlushBeforeStop = false,
            callNativeStopChannel = false,
            callNativeReferenceStop = HikProtocolSupport.USE_NATIVE_LIBUSB,
            workerJoinRetry = false,
        )

    /** StopChannel steps for [mode] — E10 default; E8/E9 kept for A/B. */
    fun stopChannelActions(
        recipe: Recipe = activeRecipe,
        mode: HikShutdownMode = HikShutdownMode.SoftPause,
    ): PostBulkQuietActions = when (recipe) {
        Recipe.E10 -> referenceSoftPause(mode)
        Recipe.E10D -> referenceSoftPause(mode).copy(
            disarm = !HikProtocolSupport.USE_NATIVE_LIBUSB,
            releaseInterfaces = !HikProtocolSupport.USE_NATIVE_LIBUSB,
            closeHandle = false,
        )
        Recipe.E8, Recipe.E9 -> legacyE8Stop(mode)
        Recipe.E1 -> stopChannelBase(recipe, mode).copy(disarm = false, releaseInterfaces = false)
        Recipe.E2 -> stopChannelBase(recipe, mode).copy(disarm = true, releaseInterfaces = false)
        Recipe.E4 -> stopChannelBase(recipe, mode).copy(
            disarm = true,
            primeBulk = true,
            releaseInterfaces = false,
        )
        Recipe.E6 -> stopChannelBase(recipe, mode).copy(disarm = false, releaseInterfaces = false)
        Recipe.E7 -> stopChannelBase(recipe, mode).copy(disarm = true, settleMs = 500L, releaseInterfaces = false)
    }

    /** Exit / detach stop phase — active recipe (E10D disarm+release on graceful exit). */
    fun stopChannelActionsForExit(): PostBulkQuietActions =
        stopChannelActions(activeRecipe, HikShutdownMode.GracefulExit)

    fun stopChannelActionsForDetach(): PostBulkQuietActions =
        referenceSoftPause(HikShutdownMode.UsbDetached).copy(workerJoinMs = 200L)

    /** Full recipe including closeHandle (experiments / legacy). */
    fun actionsFor(recipe: Recipe = activeRecipe): PostBulkQuietActions =
        stopChannelActions(recipe).let { base ->
            when (recipe) {
                Recipe.E1, Recipe.E2, Recipe.E4, Recipe.E7 -> base.copy(closeHandle = true)
                Recipe.E6 -> base.copy(closeHandle = false)
                Recipe.E8, Recipe.E9, Recipe.E10, Recipe.E10D -> base
            }
        }

    private fun legacyE8Stop(mode: HikShutdownMode): PostBulkQuietActions =
        stopChannelBase(Recipe.E8, mode).copy(
            disarm = true,
            releaseInterfaces = true,
            closeHandle = false,
            libusbFlushBeforeStop = true,
            callNativeStopChannel = true,
            workerJoinMs = HikStreamStop.STOP_CHANNEL_JOIN_MS,
            requireWorkerDead = true,
            drainAfterCancel = true,
            cancelWaitMs = CANCEL_WAIT_FULL_MS,
            workerJoinRetry = true,
        )

    private fun stopChannelBase(recipe: Recipe, mode: HikShutdownMode): PostBulkQuietActions =
        PostBulkQuietActions(
            shutdownMode = mode,
            workerJoinMs = HikStreamStop.STOP_CHANNEL_JOIN_MS,
            requireWorkerDead = true,
            cancelBulkFirst = true,
        )

    const val CANCEL_WAIT_SOFT_MS = 200
    const val CANCEL_WAIT_FULL_MS = 3_000
}
