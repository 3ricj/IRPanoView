package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.model.QuadCameraOrder
import kotlin.math.roundToInt

/**
 * Per-camera affine correction hook (currently all zero).
 *
 * Keyed by USB machine-serial suffix ([QuadCameraOrder.SERIAL_SUFFIXES]) so offsets
 * can survive hotplug bus-path changes. Applied in [HikTherm.storedU16FromRaw] on top of
 * protocol bias **0x37C0** (stored-domain delta = offsetCelsius × 64).
 *
 * Cloth-derived offsets (v1-v3, 2026-05-25) were disabled: they drift strongly with
 * scene temperature and FOV. Raw protocol decode only until a better model exists.
 */
object HikRadiometricCalibration {

    /** Affine °C offset: corrected = decode(raw) + offsetCelsius(serial). */
    private val OFFSET_C_BY_FULL_SERIAL: Map<String, Double> = mapOf(
        "EA6744502" to 0.00,
        "EA6744407" to 0.00,
        "EA6462986" to 0.00,
        "EA6473497" to 0.00,
    )

    /** Suffix fallback when full serial differs from quad reference units. */
    private val OFFSET_C_BY_SUFFIX: Map<String, Double> = mapOf(
        "02" to 0.00,
        "07" to 0.00,
        "86" to 0.00,
        "97" to 0.00,
    )

    fun offsetCelsius(serial: String?): Double {
        val trimmed = serial?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            OFFSET_C_BY_FULL_SERIAL[trimmed]?.let { return it }
        }
        val suffix = serialSuffix(serial) ?: return 0.0
        return OFFSET_C_BY_SUFFIX[suffix] ?: 0.0
    }

    /** Stored-u16 correction applied with protocol bias: offsetCelsius × [HikTherm.TEMP_SCALE]. */
    fun biasDeltaU16(serial: String?): Int {
        val offC = offsetCelsius(serial)
        if (offC == 0.0) return 0
        return (offC * HikTherm.TEMP_SCALE).roundToInt()
    }

    /** Full serial string for logging (may be null if USB permission missing). */
    fun describeOffset(serial: String?): String {
        val off = offsetCelsius(serial)
        val suffix = serialSuffix(serial)
        return when {
            serial.isNullOrBlank() -> "serial=? offset=${"%.2f".format(off)}C"
            suffix != null -> "serial=$serial suffix=$suffix offset=${"%.2f".format(off)}C"
            else -> "serial=$serial offset=${"%.2f".format(off)}C"
        }
    }

    fun isCalibrated(serial: String?): Boolean {
        val trimmed = serial?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed in OFFSET_C_BY_FULL_SERIAL) return true
        return serialSuffix(serial) in OFFSET_C_BY_SUFFIX
    }

    private fun serialSuffix(serial: String?): String? {
        val trimmed = serial?.trim().orEmpty()
        if (trimmed.length < 2) return null
        return trimmed.takeLast(2)
    }
}
