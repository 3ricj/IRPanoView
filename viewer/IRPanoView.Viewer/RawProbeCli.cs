using IRPanoView.Viewer.Display;
using IRPanoView.Viewer.Network;

namespace IRPanoView.Viewer;

// Headless diagnostic: drives the real RawCameraStreamClient against the Pi and
// reports per-camera frame counts + temps. Exercises the exact networking,
// parsing, and inflate path the GUI uses.
//   IRPanoView.Viewer --probe-raw [--host IP] [--port N] [--seconds S]
internal static class RawProbeCli
{
    public static int TryRun(string[] args)
    {
        if (!args.Contains("--probe-raw", StringComparer.OrdinalIgnoreCase))
        {
            return -1;
        }

        var host = "192.168.8.114";
        var port = 8767;
        var seconds = 6;
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i].ToLowerInvariant())
            {
                case "--host" when i + 1 < args.Length:
                    host = args[++i];
                    break;
                case "--port" when i + 1 < args.Length:
                    int.TryParse(args[++i], out port);
                    break;
                case "--seconds" when i + 1 < args.Length:
                    int.TryParse(args[++i], out seconds);
                    break;
            }
        }

        Console.WriteLine($"probe-raw host={host} port={port} seconds={seconds}");
        var client = new RawCameraStreamClient();
        client.Configure(host, port);
        client.Start();

        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (sw.Elapsed.TotalSeconds < seconds)
        {
            Thread.Sleep(1000);
            var counts = string.Join(" ", Enumerable.Range(1, 4).Select(s => $"cam{s}={client.FrameCount(s)}"));
            Console.WriteLine($"[{sw.Elapsed.TotalSeconds:F0}s] {counts}  err={client.LastError ?? "none"}");
        }

        var latest = client.SnapshotLatest();
        for (var s = 1; s <= RawCameraStreamClient.CameraCount; s++)
        {
            var f = latest[s];
            if (f == null)
            {
                Console.WriteLine($"cam{s}: NO FRAME");
                continue;
            }

            var px = f.Parsed.RawPixels;
            double mn = double.MaxValue, mx = double.MinValue, sum = 0;
            foreach (var p in px)
            {
                var c = HikTherm.CelsiusFromRawU16(p);
                if (c < mn) mn = c;
                if (c > mx) mx = c;
                sum += c;
            }

            Console.WriteLine(
                $"cam{s}: {f.Parsed.Width}x{f.Parsed.Height} serial={f.Parsed.CameraSerial} " +
                $"tempsC min={mn:F1} avg={sum / px.Length:F1} max={mx:F1}");
        }

        client.Stop();
        Console.WriteLine("done");
        return 0;
    }
}
