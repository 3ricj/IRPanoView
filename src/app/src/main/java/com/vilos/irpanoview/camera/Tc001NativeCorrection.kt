package com.vilos.irpanoview.camera

import android.content.Context
import com.energy.irutilslibrary.LibIRTemp
import java.util.LinkedHashMap

/**
 * Native TC001 correction bridge using vendor correction JNI.
 *
 * This intentionally uses vendor correction math (new-method path) with tau asset input,
 * and avoids host-side affine/heuristic temperature remapping.
 */
class Tc001NativeCorrection private constructor(
    private val tauBytes: ByteArray,
    emissivity: Float,
    ambientCelsius: Float,
    reflectionCelsius: Float,
    distanceMeters: Float,
    humidityRatio: Float,
) {
    @Volatile private var emissivity: Float = emissivity
    @Volatile private var ambientCelsius: Float = ambientCelsius
    @Volatile private var reflectionCelsius: Float = reflectionCelsius
    @Volatile private var distanceMeters: Float = distanceMeters
    @Volatile private var humidityRatio: Float = humidityRatio
    @Volatile private var correctionEnabled = true
    private val correctedByRawNative = object : LinkedHashMap<Int, Double>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Double>?): Boolean = size > 4096
    }

    fun nativeToCorrectedCelsius(nativeLike: Int): Double {
        val orgC = nativeLike.toDouble() / 16.0 - 273.15
        if (!correctionEnabled) return orgC
        val cached = correctedByRawNative[nativeLike]
        if (cached != null) return cached
        val corrected = runCatching {
            LibIRTemp.temp_correction_with_new_method(
                orgC.toFloat(),
                tauBytes,
                emissivity,
                ambientCelsius,
                reflectionCelsius,
                distanceMeters,
                humidityRatio,
            ).toDouble()
        }.getOrElse {
            correctionEnabled = false
            return orgC
        }
        val stable = if (corrected.isFinite()) corrected else orgC
        correctedByRawNative[nativeLike] = stable
        return stable
    }

    fun updateFromTpdSnapshot(
        gain: Int,
        ems: Int,
        tau: Int,
        ta: Int,
        tu: Int,
        distance: Int,
    ): Boolean {
        if (listOf(gain, ems, tau, ta, tu, distance).any { it == Int.MIN_VALUE }) {
            correctionEnabled = false
            return false
        }
        if (listOf(gain, ems, tau, ta, tu, distance).all { it == 8 }) {
            correctionEnabled = false
            return false
        }
        val nextEmissivity = (ems.toFloat() / 128.0f).coerceIn(0.05f, 1.00f)
        val nextDistance = (distance.toFloat() / 128.0f).coerceIn(0.05f, 25.0f)
        emissivity = nextEmissivity
        distanceMeters = nextDistance
        correctionEnabled = true
        return true
    }

    companion object {
        private const val VID_TOPDON_NEW = 0x2BDF
        private const val VID_TOPDON_OLD = 0x3474
        private const val PID_TC001 = 0x0102
        private const val PID_TC001_MAX = 0x4962

        private const val DEFAULT_EMISSIVITY = 0.95f
        private const val DEFAULT_AMBIENT_C = 25.0f
        private const val DEFAULT_REFLECTION_C = 25.0f
        private const val DEFAULT_DISTANCE_M = 1.0f
        private const val DEFAULT_HUMIDITY = 0.50f

        fun maybeCreate(context: Context, vendorId: Int, productId: Int): Tc001NativeCorrection? {
            val supported =
                (vendorId == VID_TOPDON_NEW && productId == PID_TC001) ||
                    (vendorId == VID_TOPDON_OLD && productId == PID_TC001_MAX)
            if (!supported) return null
            val tauCandidates = listOf("tau/tau_H.bin", "tau_H.bin", "tau/tau.bin", "tau.bin")
            val tau = tauCandidates.firstNotNullOfOrNull { assetPath ->
                runCatching { context.assets.open(assetPath).use { it.readBytes() } }.getOrNull()
            } ?: return null
            val correction = Tc001NativeCorrection(
                tauBytes = tau,
                emissivity = DEFAULT_EMISSIVITY,
                ambientCelsius = DEFAULT_AMBIENT_C,
                reflectionCelsius = DEFAULT_REFLECTION_C,
                distanceMeters = DEFAULT_DISTANCE_M,
                humidityRatio = DEFAULT_HUMIDITY,
            )
            val probe = runCatching {
                LibIRTemp.temp_correction_with_new_method(
                    DEFAULT_AMBIENT_C,
                    tau,
                    DEFAULT_EMISSIVITY,
                    DEFAULT_AMBIENT_C,
                    DEFAULT_REFLECTION_C,
                    DEFAULT_DISTANCE_M,
                    DEFAULT_HUMIDITY,
                ).toDouble()
            }.getOrNull()
            if (probe == null || !probe.isFinite()) {
                return null
            }
            return correction
        }
    }
}
