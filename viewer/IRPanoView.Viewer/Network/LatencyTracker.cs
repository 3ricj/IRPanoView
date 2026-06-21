using System.Diagnostics;

namespace IRPanoView.Viewer.Network;

public sealed class LatencyTracker
{
    private readonly Queue<double> _t6MinusT0Ms = new();
    private readonly Queue<double> _t6MinusT1Ms = new();
    private readonly Queue<double> _decodeMs = new();
    private readonly object _gate = new();
    private const int MaxSamples = 300;

    public void RecordFrame(
        MetaStreamReceiver.MetaPacket? meta,
        long clientDisplayUs,
        double decodeMs)
    {
        if (meta == null)
        {
            return;
        }

        var t0Candidates = meta.SlotUsbFrameUs.Where(v => v > 0).ToList();
        if (t0Candidates.Count == 0)
        {
            return;
        }

        var t0 = t0Candidates.Min();
        var t6MinusT0 = (clientDisplayUs - t0) / 1000.0;
        var t6MinusT1 = (clientDisplayUs - meta.ComposeUs) / 1000.0;

        lock (_gate)
        {
            Enqueue(_t6MinusT0Ms, t6MinusT0);
            Enqueue(_t6MinusT1Ms, t6MinusT1);
            Enqueue(_decodeMs, decodeMs);
        }
    }

    public (double AvgT6T0, double P99T6T0, double AvgT6T1, double P99T6T1, double AvgDecode) Snapshot()
    {
        lock (_gate)
        {
            return (
                Average(_t6MinusT0Ms),
                Percentile(_t6MinusT0Ms, 99),
                Average(_t6MinusT1Ms),
                Percentile(_t6MinusT1Ms, 99),
                Average(_decodeMs));
        }
    }

    public void ExportCsv(string path)
    {
        lock (_gate)
        {
            using var writer = new StreamWriter(path, append: false);
            writer.WriteLine("sample,t6_minus_t0_ms,t6_minus_t1_ms,decode_ms");
            var n = Math.Max(_t6MinusT0Ms.Count, Math.Max(_t6MinusT1Ms.Count, _decodeMs.Count));
            var a = _t6MinusT0Ms.ToArray();
            var b = _t6MinusT1Ms.ToArray();
            var c = _decodeMs.ToArray();
            for (var i = 0; i < n; i++)
            {
                writer.WriteLine($"{i},{ValueAt(a, i):F3},{ValueAt(b, i):F3},{ValueAt(c, i):F3}");
            }
        }
    }

    private static void Enqueue(Queue<double> q, double value)
    {
        q.Enqueue(value);
        while (q.Count > MaxSamples)
        {
            q.Dequeue();
        }
    }

    private static double Average(Queue<double> q) => q.Count == 0 ? 0 : q.Average();

    private static double Percentile(Queue<double> q, double p)
    {
        if (q.Count == 0)
        {
            return 0;
        }

        var sorted = q.OrderBy(v => v).ToList();
        var idx = (int)Math.Round((p / 100.0) * (sorted.Count - 1));
        idx = Math.Clamp(idx, 0, sorted.Count - 1);
        return sorted[idx];
    }

    private static double ValueAt(double[] values, int index) =>
        index < values.Length ? values[index] : 0;
}
