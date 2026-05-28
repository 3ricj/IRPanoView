package com.vilos.irpanoview.camera.hik

import kotlin.math.abs

/** Score candidate UVC wire buffers (200704 B) for macropixel radiometry plausibility. */
internal object HikFrameAligner {

    data class SearchResult(
        val byteOffset: Int,
        val score: Double,
        val centerCelsius: Double,
    )

    const val MIN_ACCEPT_SCORE = 35.0
    const val MAX_SEARCH_BYTES = 2048
    private val PREFERRED_ALIGN_OFFSETS = intArrayOf(0, 2, 4)

    val THERMAL_DECODE_BYTES: Int = HikTherm.FRAME_BYTES

    fun score(frame: ByteArray): Double {
        if (frame.size < THERMAL_DECODE_BYTES) return Double.NEGATIVE_INFINITY
        val cx = HikTherm.GRID_WIDTH / 2
        val cy = HikTherm.GRID_HEIGHT / 2
        val center = runCatching { HikTherm.celsiusAtPixel(frame, cx, cy) }.getOrNull()
            ?: return Double.NEGATIVE_INFINITY

        var score = 0.0
        score += when {
            center in 5.0..45.0 -> 40.0
            center in -5.0..55.0 -> 15.0
            center > 120.0 || center < -35.0 -> -60.0
            else -> 0.0
        }

        var hSmooth = 0
        for (x in 1 until HikTherm.GRID_WIDTH) {
            val d = abs(
                HikTherm.celsiusAtPixel(frame, x, cy) -
                    HikTherm.celsiusAtPixel(frame, x - 1, cy),
            )
            if (d < 12.0) hSmooth++
        }
        score += hSmooth / HikTherm.GRID_WIDTH.toDouble() * 25.0

        var vSmooth = 0
        for (y in 1 until HikTherm.GRID_HEIGHT) {
            val d = abs(
                HikTherm.celsiusAtPixel(frame, cx, y) -
                    HikTherm.celsiusAtPixel(frame, cx, y - 1),
            )
            if (d < 12.0) vSmooth++
        }
        score += vSmooth / HikTherm.GRID_HEIGHT.toDouble() * 25.0

        var bad = 0
        var samples = 0
        var y = 0
        while (y < HikTherm.GRID_HEIGHT) {
            var x = 0
            while (x < HikTherm.GRID_WIDTH) {
                val c = HikTherm.celsiusAtPixel(frame, x, y)
                samples++
                if (!HikTherm.isPlausibleCelsius(c)) bad++
                x += 8
            }
            y += 8
        }
        score -= (bad.toDouble() / samples.coerceAtLeast(1)) * 80.0

        return score
    }

    fun searchBestOffset(ring: HikStreamRing, frameSize: Int): SearchResult? {
        if (ring.bufferedBytes < THERMAL_DECODE_BYTES) return null
        var bestOff = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestCenter = Double.NaN
        for (off in PREFERRED_ALIGN_OFFSETS) {
            if (ring.bufferedBytes < off + THERMAL_DECODE_BYTES) break
            val peekLen = minOf(frameSize, ring.bufferedBytes - off)
            val candidate = ring.peekFrameAt(off, peekLen) ?: continue
            val s = score(candidate)
            if (s > bestScore) {
                bestScore = s
                bestOff = off
                bestCenter = runCatching {
                    HikTherm.celsiusAtPixel(
                        candidate,
                        HikTherm.GRID_WIDTH / 2,
                        HikTherm.GRID_HEIGHT / 2,
                    )
                }.getOrDefault(Double.NaN)
            }
        }
        if (bestScore == Double.NEGATIVE_INFINITY) return null
        return SearchResult(bestOff, bestScore, bestCenter)
    }
}
