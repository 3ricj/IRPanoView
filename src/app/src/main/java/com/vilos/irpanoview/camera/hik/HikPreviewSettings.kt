package com.vilos.irpanoview.camera.hik

/** Preview tuning persisted in app settings and sent to Pi when changed. */
object HikPreviewSettings {

    const val MIN_TEMPORAL_AVERAGE_FRAMES = 1
    const val MAX_TEMPORAL_AVERAGE_FRAMES = 15
    const val DEFAULT_TEMPORAL_AVERAGE_FRAMES = 3

    @Volatile
    var temporalAverageFrames: Int = DEFAULT_TEMPORAL_AVERAGE_FRAMES
        private set

    fun setTemporalAverageFrames(count: Int) {
        temporalAverageFrames = count.coerceIn(MIN_TEMPORAL_AVERAGE_FRAMES, MAX_TEMPORAL_AVERAGE_FRAMES)
    }
}
