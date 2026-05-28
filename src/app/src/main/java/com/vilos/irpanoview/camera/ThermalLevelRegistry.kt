package com.vilos.irpanoview.camera

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared display range across all active UVC cameras so the same radiometric scalar
 * maps to the same palette index on every tile in grid mode.
 *
 * When [focusedBusPath] is set (single-camera view), each camera decodes with its own
 * smoothed Celsius window and the scale bar reflects the focused camera only.
 */
object ThermalLevelRegistry {

    private data class CamReport(val min: Int, val max: Int)
    private data class CamWindow(val minC: Double, val maxC: Double)

    private class CamWindowHistory {
        val mins = ArrayDeque<Double>(WINDOW_SMOOTH_FRAMES)
        val maxs = ArrayDeque<Double>(WINDOW_SMOOTH_FRAMES)
    }

    private val perCamera = ConcurrentHashMap<String, CamReport>()
    private val perCameraWindow = ConcurrentHashMap<String, CamWindow>()
    private val perCameraWindowHistory = ConcurrentHashMap<String, CamWindowHistory>()

    @Volatile
    private var displayMin: Int = 0

    @Volatile
    private var displayMax: Int = 65535

    @Volatile
    private var displayMinC: Double = defaultWindowMinC()

    @Volatile
    private var displayMaxC: Double = defaultWindowMaxC()

    @Volatile
    private var focusedBusPath: String? = null

    private var smoothedMin: Double = 0.0
    private var smoothedMax: Double = 65535.0
    private var smoothInitialized = false

    fun onDisplayRangeChanged(floorC: Double, ceilingC: Double) {
        displayMinC = floorC
        displayMaxC = ceilingC
        perCameraWindowHistory.clear()
    }

    fun report(busPath: String, min: Int, max: Int) {
        if (max < min) return
        perCamera[busPath] = CamReport(min, max)
        recomputeDisplayRange()
    }

    fun unregister(busPath: String) {
        perCamera.remove(busPath)
        perCameraWindow.remove(busPath)
        perCameraWindowHistory.remove(busPath)
        if (focusedBusPath == busPath) focusedBusPath = null
        recomputeDisplayRange()
        recomputeDisplayWindow()
    }

    fun setFocusedBusPath(busPath: String?) {
        focusedBusPath = busPath
    }

    fun displayMin(): Int = displayMin

    fun displayMax(): Int = displayMax

    fun activeCameraCount(): Int = perCamera.size

    fun snapshot(): GlobalLevels = GlobalLevels(
        displayMin = displayMin,
        displayMax = displayMax,
        perCamera = perCamera.mapValues { it.value.min to it.value.max },
    )

    fun reportWindow(busPath: String, minC: Double, maxC: Double) {
        if (!minC.isFinite() || !maxC.isFinite() || maxC <= minC) return
        val history = perCameraWindowHistory.getOrPut(busPath) { CamWindowHistory() }
        synchronized(history) {
            history.mins.addLast(minC)
            if (history.mins.size > WINDOW_SMOOTH_FRAMES) history.mins.removeFirst()
            history.maxs.addLast(maxC)
            if (history.maxs.size > WINDOW_SMOOTH_FRAMES) history.maxs.removeFirst()
            perCameraWindow[busPath] = CamWindow(
                minC = history.mins.average(),
                maxC = history.maxs.average(),
            )
        }
        recomputeDisplayWindow()
    }

    /** Scale bar and other UI: focused camera window when in single-camera view, else global. */
    fun displayWindow(): TopdonFrameDecoder.CelsiusWindow {
        focusedBusPath?.let { path ->
            celsiusWindowFor(path)?.let { return it }
        }
        return globalDisplayWindow()
    }

    /** Decode path: global window in grid mode; per-camera window when a camera is focused. */
    fun displayWindowFor(busPath: String): TopdonFrameDecoder.CelsiusWindow {
        if (focusedBusPath != null) {
            celsiusWindowFor(busPath)?.let { return it }
        }
        return globalDisplayWindow()
    }

    /** Per-camera smoothed Celsius window when reported via [reportWindow]; null if unknown. */
    fun windowFor(busPath: String): Pair<Double, Double>? {
        val w = perCameraWindow[busPath] ?: return null
        return w.minC to w.maxC
    }

    private fun recomputeDisplayRange() {
        if (perCamera.isEmpty()) {
            displayMin = 0
            displayMax = 65535
            smoothInitialized = false
            return
        }
        val gMin = perCamera.values.minOf { it.min }
        val gMax = perCamera.values.maxOf { it.max }
        if (!smoothInitialized) {
            smoothedMin = gMin.toDouble()
            smoothedMax = gMax.toDouble()
            smoothInitialized = true
        } else {
            smoothedMin = smoothedMin * (1.0 - EMA_ALPHA) + gMin * EMA_ALPHA
            smoothedMax = smoothedMax * (1.0 - EMA_ALPHA) + gMax * EMA_ALPHA
        }
        displayMin = smoothedMin.roundToInt()
        displayMax = max(displayMin + 1, smoothedMax.roundToInt())
    }

    private fun globalDisplayWindow(): TopdonFrameDecoder.CelsiusWindow = TopdonFrameDecoder.CelsiusWindow(
        minCelsius = displayMinC,
        maxCelsius = displayMaxC,
    )

    private fun celsiusWindowFor(busPath: String): TopdonFrameDecoder.CelsiusWindow? {
        val w = perCameraWindow[busPath] ?: return null
        return TopdonFrameDecoder.CelsiusWindow(minCelsius = w.minC, maxCelsius = w.maxC)
    }

    private fun recomputeDisplayWindow() {
        if (perCameraWindow.isEmpty()) {
            displayMinC = defaultWindowMinC()
            displayMaxC = defaultWindowMaxC()
            return
        }
        val gMin = perCameraWindow.values.minOf { it.minC }
        val gMax = perCameraWindow.values.maxOf { it.maxC }
        displayMinC = gMin
        displayMaxC = max(displayMinC + MIN_WINDOW_SPAN_C, gMax)
    }

    data class GlobalLevels(
        val displayMin: Int,
        val displayMax: Int,
        val perCamera: Map<String, Pair<Int, Int>>,
    )

    private fun defaultWindowMinC(): Double = ThermalDisplaySettings.floorCelsius

    private fun defaultWindowMaxC(): Double = ThermalDisplaySettings.ceilingCelsius

    private const val EMA_ALPHA = 0.25
    private const val MIN_WINDOW_SPAN_C = 1.0
    private const val WINDOW_SMOOTH_FRAMES = 5
}
