package com.vilos.irpanoview.camera.hik

/** Live preview tuning read by Hik camera worker threads. */
object HikPreviewSettings {

    const val MIN_TEMPORAL_AVERAGE_FRAMES = 1
    const val MAX_TEMPORAL_AVERAGE_FRAMES = 15
    const val DEFAULT_TEMPORAL_AVERAGE_FRAMES = 3

    @Volatile
    var temporalAverageFrames: Int = DEFAULT_TEMPORAL_AVERAGE_FRAMES
        private set

    const val DYNAMIC_COMP_DEFAULT_ENABLED = true
    const val DYNAMIC_COMP_MIN_OVERLAP_COLUMNS = 1
    const val DYNAMIC_COMP_MAX_OVERLAP_COLUMNS = 24
    const val DYNAMIC_COMP_DEFAULT_OVERLAP_COLUMNS = 5
    const val DYNAMIC_COMP_MIN_CENTER_BAND_RATIO = 0.10
    const val DYNAMIC_COMP_MAX_CENTER_BAND_RATIO = 1.0
    const val DYNAMIC_COMP_DEFAULT_CENTER_BAND_RATIO = 0.50

    @Volatile
    var dynamicCompEnabled: Boolean = DYNAMIC_COMP_DEFAULT_ENABLED
        private set

    @Volatile
    var dynamicCompOverlapColumns: Int = DYNAMIC_COMP_DEFAULT_OVERLAP_COLUMNS
        private set

    @Volatile
    var dynamicCompCenterBandRatio: Double = DYNAMIC_COMP_DEFAULT_CENTER_BAND_RATIO
        private set

    /** Debug: grayscale wire payload at EOF/FID instead of thermal decode. */
    const val RAW_WIRE_DISPLAY = false

    /**
     * NUC / shutter host path gate.
     * false = enabled (auto-shutter arm + manual 0x7E9 paths active)
     * true  = disabled (host NUC/shutter commands suppressed)
     */
    const val BLACK_REFERENCE_DESCOPED = true

    /** @deprecated use [BLACK_REFERENCE_DESCOPED] */
    @Deprecated("Use BLACK_REFERENCE_DESCOPED", ReplaceWith("BLACK_REFERENCE_DESCOPED"))
    const val SUPPRESS_BLACK_REFERENCE = BLACK_REFERENCE_DESCOPED

    fun setTemporalAverageFrames(count: Int) {
        val clamped = count.coerceIn(MIN_TEMPORAL_AVERAGE_FRAMES, MAX_TEMPORAL_AVERAGE_FRAMES)
        if (clamped == temporalAverageFrames) return
        temporalAverageFrames = clamped
        HikCameraRegistry.resetTemporalAveragers()
    }

    fun setDynamicCompEnabled(enabled: Boolean) {
        if (dynamicCompEnabled == enabled) return
        dynamicCompEnabled = enabled
        if (!enabled) {
            HikDynamicTemperatureCompensation.resetAll()
        }
    }

    fun setDynamicCompOverlapColumns(count: Int) {
        dynamicCompOverlapColumns = count.coerceIn(
            DYNAMIC_COMP_MIN_OVERLAP_COLUMNS,
            DYNAMIC_COMP_MAX_OVERLAP_COLUMNS,
        )
    }

    fun setDynamicCompCenterBandRatio(ratio: Double) {
        dynamicCompCenterBandRatio = ratio.coerceIn(
            DYNAMIC_COMP_MIN_CENTER_BAND_RATIO,
            DYNAMIC_COMP_MAX_CENTER_BAND_RATIO,
        )
    }
}
