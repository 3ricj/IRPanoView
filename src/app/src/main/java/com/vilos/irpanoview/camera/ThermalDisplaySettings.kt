package com.vilos.irpanoview.camera

import com.vilos.irpanoview.camera.hik.HikFrameDecoder
import com.vilos.irpanoview.camera.hik.HikTherm
import kotlin.math.max
import kotlin.math.min

/** Shared display-range floor/ceiling (°C) for all thermal decode paths. */
object ThermalDisplaySettings {

    const val DEFAULT_FLOOR_C = 20.0
    const val DEFAULT_CEILING_C = 40.0
    const val MIN_SPAN_C = 2.0

    val sensorMinC: Double = HikTherm.PLAUSIBLE_MIN_C
    val sensorMaxC: Double = HikTherm.PLAUSIBLE_MAX_C

    @Volatile
    var floorCelsius: Double = defaultFloorCelsius()
        private set

    @Volatile
    var ceilingCelsius: Double = defaultCeilingCelsius()
        private set

    fun defaultFloorCelsius(): Double =
        DEFAULT_FLOOR_C.coerceIn(sensorMinC, sensorMaxC - MIN_SPAN_C)

    fun defaultCeilingCelsius(): Double =
        DEFAULT_CEILING_C.coerceIn(defaultFloorCelsius() + MIN_SPAN_C, sensorMaxC)

    fun setRange(floorC: Double, ceilingC: Double) {
        val floor = floorC.coerceIn(sensorMinC, sensorMaxC - MIN_SPAN_C)
        val ceiling = ceilingC.coerceIn(floor + MIN_SPAN_C, sensorMaxC)
        if (floor == floorCelsius && ceiling == ceilingCelsius) return
        floorCelsius = floor
        ceilingCelsius = ceiling
    }

    fun hikDynamicWindowPolicy(): HikFrameDecoder.DynamicWindowPolicy =
        HikFrameDecoder.DynamicWindowPolicy(
            hardMinCelsius = floorCelsius,
            hardMaxCelsius = ceilingCelsius,
            minSpanCelsius = MIN_SPAN_C,
        )

    fun topdonDynamicWindowPolicy(): TopdonFrameDecoder.DynamicWindowPolicy =
        TopdonFrameDecoder.DynamicWindowPolicy(
            hardMinCelsius = floorCelsius,
            hardMaxCelsius = ceilingCelsius,
            minSpanCelsius = MIN_SPAN_C,
        )

    fun clampFloor(candidate: Double, ceiling: Double = ceilingCelsius): Double =
        candidate.coerceIn(sensorMinC, max(sensorMinC, ceiling - MIN_SPAN_C))

    fun clampCeiling(candidate: Double, floor: Double = floorCelsius): Double =
        candidate.coerceIn(min(sensorMaxC, floor + MIN_SPAN_C), sensorMaxC)
}
