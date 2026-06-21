using System.Drawing.Imaging;
using System.Text;
using System.Text.Json;
using IRPanoView.Viewer.Display;
using IRPanoView.Viewer.Network;

namespace IRPanoView.Viewer;

internal static class SnapshotExporter
{
    private const int TileWidth = 256;
    private const int TileHeight = 192;

    public sealed record SnapshotOptions
    {
        public int Port { get; init; } = 8765;
        public int TimeoutSeconds { get; init; } = 30;
        public string OutputDirectory { get; init; } = "snapshots";
        public double FloorC { get; init; } = 20;
        public double CeilingC { get; init; } = 40;
        public ThermalColorPalette Palette { get; init; } = ThermalColorPalette.Jet;
    }

    public static int Run(SnapshotOptions options)
    {
        Console.WriteLine($"Listening UDP {options.Port} for one IRPV frame (timeout {options.TimeoutSeconds}s)...");
        var captured = ThermalFrameCapture.CaptureOne(options.Port, TimeSpan.FromSeconds(options.TimeoutSeconds));
        if (captured == null)
        {
            Console.Error.WriteLine("No complete frame received.");
            return 1;
        }

        Directory.CreateDirectory(options.OutputDirectory);
        var frame = captured.Frame;
        var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
        var baseName = Path.Combine(options.OutputDirectory, $"snapshot-{stamp}-seq{frame.Sequence}");

        File.WriteAllBytes($"{baseName}.irpv", captured.RawPacket);

        var payloadPath = $"{baseName}-payload-u16.bin";
        using (var stream = File.OpenWrite(payloadPath))
        using (var writer = new BinaryWriter(stream))
        {
            foreach (var raw in frame.RawPixels)
            {
                writer.Write(raw);
            }
        }

        var auto = PanoFrameDecoder.DecodeFlatRawGrid(
            frame.RawPixels, frame.Width, frame.Height, options.Palette, options.FloorC, options.CeilingC, autoRange: true);
        auto.Bitmap.Save($"{baseName}-preview-auto.png", ImageFormat.Png);
        auto.Bitmap.Dispose();

        var manual = PanoFrameDecoder.DecodeFlatRawGrid(
            frame.RawPixels, frame.Width, frame.Height, options.Palette, options.FloorC, options.CeilingC, autoRange: false);
        manual.Bitmap.Save($"{baseName}-preview-manual.png", ImageFormat.Png);
        manual.Bitmap.Dispose();

        var report = BuildReport(captured, auto.WindowMinC, auto.WindowMaxC, manual.WindowMinC, manual.WindowMaxC, options);
        var json = JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText($"{baseName}.json", json, Encoding.UTF8);

        File.WriteAllLines($"{baseName}-datagrams.txt", captured.DatagramLog, Encoding.UTF8);

        Console.WriteLine($"Saved snapshot seq={frame.Sequence} to {options.OutputDirectory}");
        Console.WriteLine($"  {Path.GetFileName(baseName)}.irpv ({captured.RawPacket.Length} bytes)");
        Console.WriteLine($"  {Path.GetFileName(baseName)}.json");
        Console.WriteLine($"  {Path.GetFileName(baseName)}-preview-auto.png (window {auto.WindowMinC:F2}..{auto.WindowMaxC:F2} C)");
        Console.WriteLine($"  {Path.GetFileName(baseName)}-preview-manual.png (window {manual.WindowMinC:F2}..{manual.WindowMaxC:F2} C)");
        return 0;
    }

    private static object BuildReport(
        ThermalFrameCapture.CaptureResult captured,
        double autoMin,
        double autoMax,
        double manualMin,
        double manualMax,
        SnapshotOptions options)
    {
        var frame = captured.Frame;
        var raw = frame.RawPixels;
        var celsius = raw.Select(v => HikTherm.CelsiusFromRawU16(v)).ToArray();

        return new
        {
            captured_at = DateTime.Now.ToString("O"),
            source = captured.Source,
            datagram_count = captured.DatagramCount,
            chunk_datagram_count = captured.ChunkDatagramCount,
            header = new
            {
                magic = "IRPV",
                version = 1,
                flags = frame.CameraHealth != null ? ThermalFrameParser.FlagPerCameraHealth : 0,
                width = frame.Width,
                height = frame.Height,
                sequence = frame.Sequence,
                timestamp_us = frame.TimestampUs,
                raw_packet_bytes = captured.RawPacket.Length,
                header_hex = Convert.ToHexString(captured.RawPacket.AsSpan(0, Math.Min(ThermalFrameParser.HeaderBytes, captured.RawPacket.Length))),
            },
            camera_health = frame.CameraHealth?.Select(b => (int)b).ToArray(),
            frame_stats = StatsBlock("full_frame", raw, celsius, 0, 0, frame.Width, frame.Height),
            tiles = Enumerable.Range(0, 4).Select(tile =>
            {
                var x0 = tile * TileWidth;
                return StatsBlock($"tile_{tile + 1}_x{x0}", raw, celsius, x0, 0, TileWidth, TileHeight);
            }).ToArray(),
            display = new
            {
                palette = options.Palette.ToString(),
                floor_c = options.FloorC,
                ceiling_c = options.CeilingC,
                auto_window_min_c = autoMin,
                auto_window_max_c = autoMax,
                manual_window_min_c = manualMin,
                manual_window_max_c = manualMax,
            },
        };
    }

    private static object StatsBlock(
        string name,
        ushort[] raw,
        double[] celsius,
        int x0,
        int y0,
        int width,
        int height,
        int fullWidth = ThermalFrameParser.PanoWidth)
    {
        var rawSamples = new List<ushort>();
        var cSamples = new List<double>();
        for (var y = y0; y < y0 + height; y++)
        {
            for (var x = x0; x < x0 + width; x++)
            {
                var i = y * fullWidth + x;
                if (i < 0 || i >= raw.Length)
                {
                    continue;
                }

                rawSamples.Add(raw[i]);
                cSamples.Add(celsius[i]);
            }
        }

        rawSamples.Sort();
        cSamples.Sort();
        var cx = x0 + width / 2;
        var cy = y0 + height / 2;
        var center = cy * fullWidth + cx;

        return new
        {
            name,
            x = x0,
            y = y0,
            width,
            height,
            pixel_count = rawSamples.Count,
            raw_u16 = new
            {
                min = rawSamples[0],
                max = rawSamples[^1],
                p05 = Percentile(rawSamples, 5),
                p50 = Percentile(rawSamples, 50),
                p95 = Percentile(rawSamples, 95),
            },
            celsius = new
            {
                min = cSamples[0],
                max = cSamples[^1],
                p05 = Percentile(cSamples, 5),
                p50 = Percentile(cSamples, 50),
                p95 = Percentile(cSamples, 95),
            },
            center_pixel = center < raw.Length
                ? new
                {
                    x = cx,
                    y = cy,
                    raw_u16 = raw[center],
                    celsius = celsius[center],
                }
                : null,
        };
    }

    private static double Percentile(IReadOnlyList<double> values, double p)
    {
        var idx = (int)Math.Round((p / 100.0) * (values.Count - 1));
        return values[Math.Clamp(idx, 0, values.Count - 1)];
    }

    private static ushort Percentile(IReadOnlyList<ushort> values, double p)
    {
        var idx = (int)Math.Round((p / 100.0) * (values.Count - 1));
        return values[Math.Clamp(idx, 0, values.Count - 1)];
    }
}
