package com.vilos.irpanoview.camera

/**
 * Converts raw LE16 thermal samples to Celsius.
 *
 * This keeps the conversion model explicit and replaceable while we work toward
 * better parity with vendor sampling/calibration behavior.
 */
enum class NativeValueSource {
    RAW16_LE,
}

data class TemperatureModel(
    val name: String,
    val source: NativeValueSource,
    val scale: Int,
    val notes: String,
    val isDebugFallback: Boolean = false,
) {
    fun rawToNativeLike(raw: Int): Int {
        return when (source) {
            NativeValueSource.RAW16_LE -> raw
        }
    }

    fun nativeLikeToCelsius(nativeLike: Int): Double {
        return nativeLike.toDouble() / scale.toDouble() - 273.15
    }

    fun rawToCelsius(raw: Int): Double {
        return nativeLikeToCelsius(rawToNativeLike(raw))
    }

    companion object {
        fun apkRuntimeReimplV1Tc001(): TemperatureModel = TemperatureModel(
            name = "apk_runtime_reimpl_v1_tc001",
            source = NativeValueSource.RAW16_LE,
            scale = 16,
            notes = "APK-like runtime branch: temperature source + scale=16 conversion stage.",
        )

        fun debugFallbackDecode64(): TemperatureModel = TemperatureModel(
            name = "debug_fallback_decode64",
            source = NativeValueSource.RAW16_LE,
            scale = 64,
            notes = "Debug-only fallback for comparison; not APK runtime target.",
            isDebugFallback = true,
        )
    }
}

object TemperatureModelRegistry {
    private const val VID_TOPDON_NEW = 0x2BDF
    private const val VID_TOPDON_OLD = 0x3474
    private const val PID_TC001 = 0x0102
    private const val PID_TC001_MAX = 0x4962

    fun forUsbDevice(vendorId: Int, productId: Int): TemperatureModel {
        if (
            (vendorId == VID_TOPDON_NEW && productId == PID_TC001) ||
            (vendorId == VID_TOPDON_OLD && productId == PID_TC001_MAX)
        ) {
            return TemperatureModel.apkRuntimeReimplV1Tc001()
        }
        return TemperatureModel.debugFallbackDecode64()
    }
}
