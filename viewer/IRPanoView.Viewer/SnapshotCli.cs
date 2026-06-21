using IRPanoView.Viewer.Display;

namespace IRPanoView.Viewer;

internal static class SnapshotCli
{
    public static int TryRun(string[] args)
    {
        if (args.Length == 0 || !args.Contains("--snapshot", StringComparer.OrdinalIgnoreCase))
        {
            return -1;
        }

        var options = new SnapshotExporter.SnapshotOptions();
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i].ToLowerInvariant())
            {
                case "--snapshot":
                    break;
                case "--port" when i + 1 < args.Length:
                    options = options with { Port = int.Parse(args[++i]) };
                    break;
                case "--timeout" when i + 1 < args.Length:
                    options = options with { TimeoutSeconds = int.Parse(args[++i]) };
                    break;
                case "--out" when i + 1 < args.Length:
                    options = options with { OutputDirectory = args[++i] };
                    break;
                case "--floor" when i + 1 < args.Length:
                    options = options with { FloorC = double.Parse(args[++i]) };
                    break;
                case "--ceiling" when i + 1 < args.Length:
                    options = options with { CeilingC = double.Parse(args[++i]) };
                    break;
                case "--palette" when i + 1 < args.Length:
                    if (Enum.TryParse<ThermalColorPalette>(args[++i], ignoreCase: true, out var palette))
                    {
                        options = options with { Palette = palette };
                    }
                    break;
                case "--help":
                case "-h":
                    PrintHelp();
                    return 0;
            }
        }

        return SnapshotExporter.Run(options);
    }

    private static void PrintHelp()
    {
        Console.WriteLine("""
            IRPanoView.Viewer --snapshot [options]

              Capture one IRPV frame from the Pi UDP stream and write inspection artifacts.

            Options:
              --port N        UDP listen port (default 8765)
              --timeout SEC   Wait timeout (default 30)
              --out DIR       Output directory (default ./snapshots)
              --floor C       Manual preview floor °C (default 20)
              --ceiling C     Manual preview ceiling °C (default 40)
              --palette NAME  Jet, Ironbow, Rainbow, ... (default Jet)

            Outputs per capture:
              snapshot-<stamp>-seq<N>.irpv           full reassembled wire packet
              snapshot-<stamp>-seq<N>.json           header + per-tile stats
              snapshot-<stamp>-seq<N>-payload-u16.bin raw radiometry payload
              snapshot-<stamp>-seq<N>-preview-auto.png
              snapshot-<stamp>-seq<N>-preview-manual.png
              snapshot-<stamp>-seq<N>-datagrams.txt  UDP chunk log
            """);
    }
}
