package com.vilos.irpanoview.camera.hik

import kotlin.math.roundToInt

/**
 * User thermometry scene params (TopInfrared `setIrConfig` → GET/SET **0x7EF**).
 *
 * Wire encodings: emissivity ×100, distance metres ×100, ambient `(°C×100)+10000`.
 */
object HikIrConfigSettings {

    const val DEFAULT_EMISSIVITY = 0.95
    const val DEFAULT_DISTANCE_M = 10.0
    const val DEFAULT_AMBIENT_C = 20.0

    const val MIN_EMISSIVITY = 0.01
    const val MAX_EMISSIVITY = 1.0
    const val MIN_DISTANCE_M = 0.5
    const val MAX_DISTANCE_M = 50.0
    const val MIN_AMBIENT_C = -20.0
    const val MAX_AMBIENT_C = 60.0

    @Volatile
    var emissivity: Double = DEFAULT_EMISSIVITY
        private set

    @Volatile
    var distanceM: Double = DEFAULT_DISTANCE_M
        private set

    @Volatile
    var ambientCelsius: Double = DEFAULT_AMBIENT_C
        private set

    fun set(emissivity: Double, distanceM: Double, ambientCelsius: Double) {
        this.emissivity = emissivity.coerceIn(MIN_EMISSIVITY, MAX_EMISSIVITY)
        this.distanceM = distanceM.coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)
        this.ambientCelsius = ambientCelsius.coerceIn(MIN_AMBIENT_C, MAX_AMBIENT_C)
    }

    fun emissivityWire(): Int = (emissivity * 100.0).roundToInt().coerceIn(1, 100)

    fun distanceWire(): Int = (distanceM * 100.0).roundToInt().coerceIn(0, 500_000)

    fun ambientWire(): Int = (ambientCelsius * 100.0).roundToInt() + 10_000
}
