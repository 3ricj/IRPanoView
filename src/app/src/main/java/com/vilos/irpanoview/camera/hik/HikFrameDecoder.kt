package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.camera.ThermalColormap
import com.vilos.irpanoview.camera.TopdonFrameDecoder
import com.vilos.irpanoview.model.ThermalColorPalette
import kotlin.math.max

/** Decode Hik 200704 B UVC wire frames — macropixel radiometry band only. */
object HikFrameDecoder {

    data class DynamicWindowPolicy(
        val hardMinCelsius: Double,
        val hardMaxCelsius: Double,
        val minSpanCelsius: Double = 2.0,
    )

    data class WindowScan(
        val windowMinC: Double,
        val windowMaxC: Double,
        val sampleCount: Int,
    )

    data class RawWindowScan(
        val windowMinRaw: Int,
        val windowMaxRaw: Int,
    )

    fun scanCelsiusWindow(
        frame: ByteArray,
        policy: DynamicWindowPolicy,
        serial: String? = null,
        additionalOffsetC: Double = 0.0,
        percentileLow: Double = 5.0,
        percentileHigh: Double = 95.0,
        sampleStride: Int = 1,
    ): WindowScan {
        val stride = sampleStride.coerceIn(1, 16)
        val samples = ArrayList<Double>(HikTherm.GRID_SAMPLES / (stride * stride * 2))
        var y = 0
        while (y < HikTherm.GRID_HEIGHT) {
            var x = 0
            while (x < HikTherm.GRID_WIDTH) {
                val c = HikTherm.celsiusAtPixel(frame, x, y, serial) + additionalOffsetC
                samples.add(c)
                x += stride
            }
            y += stride
        }
        if (samples.isEmpty()) {
            return WindowScan(
                windowMinC = 0.0,
                windowMaxC = policy.minSpanCelsius,
                sampleCount = 0,
            )
        }
        val sorted = samples.sorted()
        val pLo = sorted[percentile(sorted.size, percentileLow)]
        val pHi = sorted[percentile(sorted.size, percentileHigh)]
        val p99 = sorted[percentile(sorted.size, 99.5)]
        val spanMax = sorted.last()
        var winMin = pLo
        var winMax = max(pHi, max(p99, spanMax - 0.5))
        if (winMax - winMin < policy.minSpanCelsius) {
            val mid = (winMin + winMax) / 2.0
            winMin = mid - policy.minSpanCelsius / 2.0
            winMax = mid + policy.minSpanCelsius / 2.0
        }
        return WindowScan(
            windowMinC = winMin,
            windowMaxC = winMax,
            sampleCount = samples.size,
        )
    }

    /** Percentile window on wire raw u16 counts (UVC composite radio band before °C bias is known). */
    fun scanRawWindow(
        frame: ByteArray,
        percentileLow: Double = 5.0,
        percentileHigh: Double = 95.0,
        sampleStride: Int = 1,
        minSpanRaw: Int = 8,
    ): RawWindowScan {
        val stride = sampleStride.coerceIn(1, 16)
        val samples = ArrayList<Int>(HikTherm.GRID_SAMPLES / (stride * stride * 2))
        var y = 0
        while (y < HikTherm.GRID_HEIGHT) {
            var x = 0
            while (x < HikTherm.GRID_WIDTH) {
                samples.add(HikTherm.rawU16AtPixel(frame, x, y))
                x += stride
            }
            y += stride
        }
        if (samples.isEmpty()) return RawWindowScan(0, minSpanRaw)
        val sorted = samples.sorted()
        var lo = sorted[percentile(sorted.size, percentileLow)]
        var hi = sorted[percentile(sorted.size, percentileHigh)]
        if (hi - lo < minSpanRaw) {
            val mid = (lo + hi) / 2
            lo = mid - minSpanRaw / 2
            hi = mid + minSpanRaw / 2
        }
        return RawWindowScan(lo, hi)
    }

    /** False-color preview from macropixel radiometry (wire rows 0..191). */
    fun decodeThermalPreview(
        frame: ByteArray,
        palette: ThermalColorPalette,
        windowMinC: Double,
        windowMaxC: Double,
        outPixels: IntArray,
        serial: String? = null,
        additionalOffsetC: Double = 0.0,
    ) {
        require(outPixels.size == HikTherm.GRID_WIDTH * HikTherm.GRID_HEIGHT)
        var o = 0
        for (y in 0 until HikTherm.GRID_HEIGHT) {
            for (x in 0 until HikTherm.GRID_WIDTH) {
                val c = HikTherm.celsiusAtPixel(frame, x, y, serial) + additionalOffsetC
                val d = TopdonFrameDecoder.celsiusToDisplay(c, windowMinC, windowMaxC)
                outPixels[o++] = ThermalColormap.color(palette, d)
            }
        }
    }

    /** False-color preview from raw u16 counts (matches offline numpy autoscale). */
    fun decodeRawPreview(
        frame: ByteArray,
        palette: ThermalColorPalette,
        windowMinRaw: Int,
        windowMaxRaw: Int,
        outPixels: IntArray,
    ) {
        require(outPixels.size == HikTherm.GRID_WIDTH * HikTherm.GRID_HEIGHT)
        val span = (windowMaxRaw - windowMinRaw).coerceAtLeast(1)
        var o = 0
        for (y in 0 until HikTherm.GRID_HEIGHT) {
            for (x in 0 until HikTherm.GRID_WIDTH) {
                val raw = HikTherm.rawU16AtPixel(frame, x, y)
                val d = ((raw - windowMinRaw).toDouble() / span).coerceIn(0.0, 1.0)
                outPixels[o++] = ThermalColormap.color(palette, (d * 255.0).toInt().coerceIn(0, 255))
            }
        }
    }

    private fun percentile(n: Int, pct: Double): Int {
        if (n <= 0) return 0
        val idx = ((pct / 100.0) * (n - 1)).toInt().coerceIn(0, n - 1)
        return idx
    }
}
