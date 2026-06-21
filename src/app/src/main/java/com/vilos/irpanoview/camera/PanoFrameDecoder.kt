package com.vilos.irpanoview.camera

import android.graphics.Bitmap
import com.vilos.irpanoview.camera.hik.HikTherm
import com.vilos.irpanoview.model.ThermalColorPalette
import kotlin.math.max

object PanoFrameDecoder {

    data class DecodeResult(
        val bitmap: Bitmap,
        val windowMinC: Double,
        val windowMaxC: Double,
    )

    fun decodeFlatRawGrid(
        rawPixels: ShortArray,
        width: Int,
        height: Int,
        palette: ThermalColorPalette,
        floorC: Double,
        ceilingC: Double,
    ): DecodeResult {
        require(rawPixels.size >= width * height)
        val samples = ArrayList<Double>(rawPixels.size / 4)
        for (i in rawPixels.indices step 4) {
            val raw = rawPixels[i].toInt() and 0xFFFF
            samples.add(HikTherm.celsiusFromRawU16(raw))
        }
        val sorted = if (samples.isEmpty()) {
            listOf(floorC, ceilingC)
        } else {
            samples.sorted()
        }
        fun pct(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return floorC
            val idx = ((p / 100.0) * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
            return values[idx]
        }
        val pLo = pct(sorted, 5.0)
        val pHi = pct(sorted, 95.0)
        val p99 = pct(sorted, 99.5)
        val spanMax = sorted.last()
        var winMin = pLo
        var winMax = max(pHi, max(p99, spanMax - 0.5))
        if (winMax - winMin < 2.0) {
            val mid = (winMin + winMax) / 2.0
            winMin = mid - 1.0
            winMax = mid + 1.0
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val raw = rawPixels[i++].toInt() and 0xFFFF
                val c = HikTherm.celsiusFromRawU16(raw)
                val d = TopdonFrameDecoder.celsiusToDisplay(c, winMin, winMax)
                bitmap.setPixel(x, y, ThermalColormap.color(palette, d))
            }
        }
        return DecodeResult(bitmap, winMin, winMax)
    }

    fun syntheticDemoFrame(
        width: Int = 1024,
        height: Int = 192,
        phase: Int,
    ): ShortArray {
        val out = ShortArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cam = x / 256
                val wave = kotlin.math.sin((x + phase + cam * 30) * 0.04) * 400.0 + 8800.0 + y * 1.5
                out[y * width + x] = wave.toInt().coerceIn(0, 65535).toShort()
            }
        }
        return out
    }
}
