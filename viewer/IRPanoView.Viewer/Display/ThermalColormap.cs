namespace IRPanoView.Viewer.Display;

public static class ThermalColormap
{
    private static readonly Dictionary<ThermalColorPalette, int[]> Caches = new();

    public static int Color(ThermalColorPalette palette, int value)
    {
        if (!Caches.TryGetValue(palette, out var lut))
        {
            lut = BuildLut(palette);
            Caches[palette] = lut;
        }

        return lut[Math.Clamp(value, 0, 255)];
    }

    private static int[] BuildLut(ThermalColorPalette palette)
    {
        var lut = new int[256];
        for (var i = 0; i < 256; i++)
        {
            var t = i / 255f;
            var (r, g, b) = palette switch
            {
                ThermalColorPalette.WhiteHot => WhiteHot(t),
                ThermalColorPalette.BlackHot => BlackHot(t),
                ThermalColorPalette.Ironbow => Ironbow(t),
                ThermalColorPalette.Rainbow => Rainbow(t),
                ThermalColorPalette.RedHot => RedHot(t),
                ThermalColorPalette.Lava => Lava(t),
                ThermalColorPalette.Jet => Jet(t),
                ThermalColorPalette.Plasma => Plasma(t),
                ThermalColorPalette.Turbo => Turbo(t),
                ThermalColorPalette.Cividis => Cividis(t),
                ThermalColorPalette.Twilight => Twilight(t),
                ThermalColorPalette.GreysInverted => BlackHot(t),
                ThermalColorPalette.Inferno => Inferno(t),
                ThermalColorPalette.Magma => Magma(t),
                ThermalColorPalette.Hot => Hot(t),
                ThermalColorPalette.Viridis => Viridis(t),
                ThermalColorPalette.Grayscale => WhiteHot(t),
                _ => Jet(t),
            };
            lut[i] = unchecked((int)(0xFF000000u
                | ((uint)(r * 255) << 16)
                | ((uint)(g * 255) << 8)
                | (uint)(b * 255)));
        }

        return lut;
    }

    private static (float R, float G, float B) WhiteHot(float t) => (t, t, t);

    private static (float R, float G, float B) BlackHot(float t)
    {
        var v = 1f - t;
        return (v, v, v);
    }

    private static (float R, float G, float B) Ironbow(float t) => LerpStops(t,
    [
        (0.00f, (0.00f, 0.00f, 0.00f)),
        (0.18f, (0.12f, 0.00f, 0.55f)),
        (0.36f, (0.55f, 0.00f, 0.75f)),
        (0.54f, (0.95f, 0.10f, 0.20f)),
        (0.72f, (1.00f, 0.45f, 0.00f)),
        (0.90f, (1.00f, 0.85f, 0.20f)),
        (1.00f, (1.00f, 1.00f, 0.85f)),
    ]);

    private static (float R, float G, float B) Rainbow(float t) => HsvToRgb((1f - t) * 0.83f, 1f, 1f);

    private static (float R, float G, float B) RedHot(float t)
    {
        if (t < 0.45f)
        {
            var u = t / 0.45f;
            return (u * 0.85f, 0f, 0f);
        }

        var v = (t - 0.45f) / 0.55f;
        return (0.85f + 0.15f * v, v * 0.95f, 0f);
    }

    private static (float R, float G, float B) Lava(float t)
    {
        var r = Math.Clamp(2.4f * t, 0f, 1f);
        var g = Math.Clamp(1.8f * t - 0.15f, 0f, 1f) * (0.25f + 0.75f * t);
        var b = Math.Clamp(0.08f * (1f - t), 0f, 1f);
        return (r, g, b);
    }

    private static (float R, float G, float B) Jet(float t)
    {
        var r = Math.Clamp(1.5f - Math.Abs(4f * t - 3f), 0f, 1f);
        var g = Math.Clamp(1.5f - Math.Abs(4f * t - 2f), 0f, 1f);
        var b = Math.Clamp(1.5f - Math.Abs(4f * t - 1f), 0f, 1f);
        return (r, g, b);
    }

    private static (float R, float G, float B) Plasma(float t) => LerpStops(t,
    [
        (0.00f, (0.05f, 0.03f, 0.53f)),
        (0.25f, (0.55f, 0.09f, 0.65f)),
        (0.50f, (0.85f, 0.25f, 0.45f)),
        (0.75f, (0.97f, 0.52f, 0.20f)),
        (1.00f, (0.94f, 0.98f, 0.13f)),
    ]);

    private static (float R, float G, float B) Turbo(float t) => LerpStops(t,
    [
        (0.00f, (0.19f, 0.19f, 0.57f)),
        (0.20f, (0.09f, 0.62f, 0.69f)),
        (0.40f, (0.20f, 0.83f, 0.60f)),
        (0.60f, (0.55f, 0.90f, 0.25f)),
        (0.80f, (0.95f, 0.85f, 0.07f)),
        (1.00f, (0.48f, 0.01f, 0.01f)),
    ]);

    private static (float R, float G, float B) Cividis(float t) => LerpStops(t,
    [
        (0.00f, (0.00f, 0.13f, 0.30f)),
        (0.25f, (0.25f, 0.32f, 0.45f)),
        (0.50f, (0.55f, 0.55f, 0.50f)),
        (0.75f, (0.85f, 0.75f, 0.35f)),
        (1.00f, (1.00f, 0.95f, 0.20f)),
    ]);

    private static (float R, float G, float B) Twilight(float t) => LerpStops(t,
    [
        (0.00f, (0.09f, 0.09f, 0.29f)),
        (0.20f, (0.25f, 0.15f, 0.45f)),
        (0.40f, (0.55f, 0.35f, 0.55f)),
        (0.60f, (0.85f, 0.55f, 0.45f)),
        (0.80f, (0.95f, 0.75f, 0.35f)),
        (1.00f, (0.98f, 0.90f, 0.55f)),
    ]);

    private static (float R, float G, float B) Inferno(float t)
    {
        var r = Math.Clamp(1.2f * t - 0.1f, 0f, 1f);
        var g = Math.Clamp(2.2f * t - 0.4f, 0f, 1f) * (1f - t * 0.3f);
        var b = Math.Clamp(0.3f - t * 0.8f, 0f, 1f);
        return (r, Math.Clamp(g, 0f, 1f), b);
    }

    private static (float R, float G, float B) Magma(float t) =>
        (Math.Clamp(1.4f * t, 0f, 1f), Math.Clamp(1.8f * t - 0.3f, 0f, 1f), Math.Clamp(2.2f * t - 0.8f, 0f, 1f));

    private static (float R, float G, float B) Hot(float t) =>
        (Math.Clamp(3f * t, 0f, 1f), Math.Clamp(3f * t - 1f, 0f, 1f), Math.Clamp(3f * t - 2f, 0f, 1f));

    private static (float R, float G, float B) Viridis(float t) =>
        (0.3f + 0.7f * t, 0.2f + 0.6f * MathF.Sqrt(t), 0.5f + 0.3f * t);

    private static (float R, float G, float B) LerpStops(float t, (float Pos, (float R, float G, float B) Color)[] stops)
    {
        var clamped = Math.Clamp(t, 0f, 1f);
        for (var i = 0; i < stops.Length - 1; i++)
        {
            var (t0, c0) = stops[i];
            var (t1, c1) = stops[i + 1];
            if (clamped <= t1)
            {
                var u = t1 > t0 ? (clamped - t0) / (t1 - t0) : 0f;
                return LerpRgb(c0, c1, u);
            }
        }

        return stops[^1].Color;
    }

    private static (float R, float G, float B) LerpRgb((float R, float G, float B) a, (float R, float G, float B) b, float u) =>
        (a.R + (b.R - a.R) * u, a.G + (b.G - a.G) * u, a.B + (b.B - a.B) * u);

    private static (float R, float G, float B) HsvToRgb(float h, float s, float v)
    {
        var hue = ((h % 1f) + 1f) % 1f;
        var i = (int)(hue * 6f);
        var f = hue * 6f - i;
        var p = v * (1f - s);
        var q = v * (1f - s * f);
        var t = v * (1f - s * (1f - f));
        return (i % 6) switch
        {
            0 => (v, t, p),
            1 => (q, v, p),
            2 => (p, v, t),
            3 => (p, q, v),
            4 => (t, p, v),
            _ => (v, p, q),
        };
    }
}
