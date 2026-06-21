namespace IRPanoView.Viewer.Display;

public static class DisplayWindow
{
    public const double DefaultMinSpanC = 2.0;

    public static (double Min, double Max) Resolve(
        IReadOnlyList<double> sortedSamples,
        double floorC,
        double ceilingC,
        bool autoRange,
        double minSpanC = DefaultMinSpanC)
    {
        if (!autoRange)
        {
            var max = Math.Max(ceilingC, floorC + minSpanC);
            return (floorC, max);
        }

        if (sortedSamples.Count == 0)
        {
            return (floorC, Math.Max(ceilingC, floorC + minSpanC));
        }

        var pLo = Percentile(sortedSamples, 5.0);
        var pHi = Percentile(sortedSamples, 95.0);
        var p99 = Percentile(sortedSamples, 99.5);
        var spanMax = sortedSamples[^1];
        var winMin = pLo;
        var winMax = Math.Max(pHi, Math.Max(p99, spanMax - 0.5));
        if (winMax - winMin < minSpanC)
        {
            var mid = (winMin + winMax) / 2.0;
            winMin = mid - minSpanC / 2.0;
            winMax = mid + minSpanC / 2.0;
        }

        return (winMin, winMax);
    }

    private static double Percentile(IReadOnlyList<double> values, double p)
    {
        var idx = (int)Math.Round((p / 100.0) * (values.Count - 1));
        idx = Math.Clamp(idx, 0, values.Count - 1);
        return values[idx];
    }
}
