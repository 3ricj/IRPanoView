package com.vilos.irpanoview.camera

import androidx.compose.ui.graphics.Color
import com.vilos.irpanoview.model.ThermalColorPalette
import kotlin.math.abs
import kotlin.math.sqrt

/** Maps 0–255 thermal scalar → ARGB (false-color LUT). */
object ThermalColormap {
    private val caches = mutableMapOf<ThermalColorPalette, IntArray>()

    @Synchronized
    fun lut(palette: ThermalColorPalette): IntArray =
        caches.getOrPut(palette) { buildLut(palette) }

    fun color(palette: ThermalColorPalette, value: Int): Int =
        lut(palette)[value.coerceIn(0, 255)]

    fun composeColor(palette: ThermalColorPalette, value: Int): Color =
        Color(color(palette, value))

    private fun buildLut(palette: ThermalColorPalette): IntArray {
        return IntArray(256) { i ->
            val t = i / 255f
            val (r, g, b) = when (palette) {
                ThermalColorPalette.WhiteHot -> whiteHot(t)
                ThermalColorPalette.BlackHot -> blackHot(t)
                ThermalColorPalette.Ironbow -> ironbow(t)
                ThermalColorPalette.Rainbow -> rainbow(t)
                ThermalColorPalette.RedHot -> redHot(t)
                ThermalColorPalette.Lava -> lava(t)
                ThermalColorPalette.Jet -> jet(t)
                ThermalColorPalette.Plasma -> plasma(t)
                ThermalColorPalette.Turbo -> turbo(t)
                ThermalColorPalette.Cividis -> cividis(t)
                ThermalColorPalette.Twilight -> twilight(t)
                ThermalColorPalette.GreysInverted -> blackHot(t)
                ThermalColorPalette.Inferno -> inferno(t)
                ThermalColorPalette.Magma -> magma(t)
                ThermalColorPalette.Hot -> hot(t)
                ThermalColorPalette.Viridis -> viridis(t)
                ThermalColorPalette.Grayscale -> whiteHot(t)
            }
            (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
        }
    }

    private fun whiteHot(t: Float): Triple<Float, Float, Float> = Triple(t, t, t)

    private fun blackHot(t: Float): Triple<Float, Float, Float> {
        val v = 1f - t
        return Triple(v, v, v)
    }

    private fun ironbow(t: Float): Triple<Float, Float, Float> = lerpStops(
        t,
        arrayOf(
            0.00f to Triple(0.00f, 0.00f, 0.00f),
            0.18f to Triple(0.12f, 0.00f, 0.55f),
            0.36f to Triple(0.55f, 0.00f, 0.75f),
            0.54f to Triple(0.95f, 0.10f, 0.20f),
            0.72f to Triple(1.00f, 0.45f, 0.00f),
            0.90f to Triple(1.00f, 0.85f, 0.20f),
            1.00f to Triple(1.00f, 1.00f, 0.85f),
        ),
    )

    private fun rainbow(t: Float): Triple<Float, Float, Float> = hsvToRgb((1f - t) * 0.83f, 1f, 1f)

    private fun redHot(t: Float): Triple<Float, Float, Float> {
        if (t < 0.45f) {
            val u = t / 0.45f
            return Triple(u * 0.85f, 0f, 0f)
        }
        val u = (t - 0.45f) / 0.55f
        return Triple(0.85f + 0.15f * u, u * 0.95f, 0f)
    }

    private fun lava(t: Float): Triple<Float, Float, Float> {
        val r = (2.4f * t).coerceIn(0f, 1f)
        val g = (1.8f * t - 0.15f).coerceIn(0f, 1f) * (0.25f + 0.75f * t)
        val b = (0.08f * (1f - t)).coerceIn(0f, 1f)
        return Triple(r, g, b)
    }

    private fun jet(t: Float): Triple<Float, Float, Float> {
        val r = (1.5f - abs(4f * t - 3f)).coerceIn(0f, 1f)
        val g = (1.5f - abs(4f * t - 2f)).coerceIn(0f, 1f)
        val b = (1.5f - abs(4f * t - 1f)).coerceIn(0f, 1f)
        return Triple(r, g, b)
    }

    private fun plasma(t: Float): Triple<Float, Float, Float> = lerpStops(
        t,
        arrayOf(
            0.00f to Triple(0.05f, 0.03f, 0.53f),
            0.25f to Triple(0.55f, 0.09f, 0.65f),
            0.50f to Triple(0.85f, 0.25f, 0.45f),
            0.75f to Triple(0.97f, 0.52f, 0.20f),
            1.00f to Triple(0.94f, 0.98f, 0.13f),
        ),
    )

    private fun turbo(t: Float): Triple<Float, Float, Float> = lerpStops(
        t,
        arrayOf(
            0.00f to Triple(0.19f, 0.19f, 0.57f),
            0.20f to Triple(0.09f, 0.62f, 0.69f),
            0.40f to Triple(0.20f, 0.83f, 0.60f),
            0.60f to Triple(0.55f, 0.90f, 0.25f),
            0.80f to Triple(0.95f, 0.85f, 0.07f),
            1.00f to Triple(0.48f, 0.01f, 0.01f),
        ),
    )

    private fun cividis(t: Float): Triple<Float, Float, Float> = lerpStops(
        t,
        arrayOf(
            0.00f to Triple(0.00f, 0.13f, 0.30f),
            0.25f to Triple(0.25f, 0.32f, 0.45f),
            0.50f to Triple(0.55f, 0.55f, 0.50f),
            0.75f to Triple(0.85f, 0.75f, 0.35f),
            1.00f to Triple(1.00f, 0.95f, 0.20f),
        ),
    )

    private fun twilight(t: Float): Triple<Float, Float, Float> = lerpStops(
        t,
        arrayOf(
            0.00f to Triple(0.09f, 0.09f, 0.29f),
            0.20f to Triple(0.25f, 0.15f, 0.45f),
            0.40f to Triple(0.55f, 0.35f, 0.55f),
            0.60f to Triple(0.85f, 0.55f, 0.45f),
            0.80f to Triple(0.95f, 0.75f, 0.35f),
            1.00f to Triple(0.98f, 0.90f, 0.55f),
        ),
    )

    private fun inferno(t: Float): Triple<Float, Float, Float> {
        val r = (1.2f * t - 0.1f).coerceIn(0f, 1f)
        val g = (2.2f * t - 0.4f).coerceIn(0f, 1f) * (1f - t * 0.3f)
        val b = (0.3f - t * 0.8f).coerceIn(0f, 1f)
        return Triple(r, g.coerceIn(0f, 1f), b)
    }

    private fun magma(t: Float): Triple<Float, Float, Float> =
        Triple((1.4f * t).coerceIn(0f, 1f), (1.8f * t - 0.3f).coerceIn(0f, 1f), (2.2f * t - 0.8f).coerceIn(0f, 1f))

    private fun hot(t: Float): Triple<Float, Float, Float> =
        Triple((3f * t).coerceIn(0f, 1f), (3f * t - 1f).coerceIn(0f, 1f), (3f * t - 2f).coerceIn(0f, 1f))

    private fun viridis(t: Float): Triple<Float, Float, Float> =
        Triple(0.3f + 0.7f * t, 0.2f + 0.6f * sqrt(t), 0.5f + 0.3f * t)

    private fun lerpStops(
        t: Float,
        stops: Array<Pair<Float, Triple<Float, Float, Float>>>,
    ): Triple<Float, Float, Float> {
        val clamped = t.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            if (clamped <= t1) {
                val u = if (t1 > t0) (clamped - t0) / (t1 - t0) else 0f
                return lerpRgb(c0, c1, u)
            }
        }
        return stops.last().second
    }

    private fun lerpRgb(
        a: Triple<Float, Float, Float>,
        b: Triple<Float, Float, Float>,
        u: Float,
    ): Triple<Float, Float, Float> = Triple(
        a.first + (b.first - a.first) * u,
        a.second + (b.second - a.second) * u,
        a.third + (b.third - a.third) * u,
    )

    /** Hue in [0, 1); saturation and value in [0, 1]. */
    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
        val hue = ((h % 1f) + 1f) % 1f
        val i = (hue * 6f).toInt()
        val f = hue * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        return when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
    }
}
