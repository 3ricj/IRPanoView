using System.Drawing.Imaging;
using IRPanoView.Viewer.Display;

namespace IRPanoView.Viewer;

internal static class TileJetExporter
{
    private const int TileWidth = 256;
    private const int TileHeight = 192;

    public static int ConvertBinToJpeg(string inputPath, string outputPath, ThermalColorPalette palette, double floorC, double ceilingC)
    {
        if (!File.Exists(inputPath))
        {
            Console.Error.WriteLine($"Input not found: {inputPath}");
            return 1;
        }

        var bytes = File.ReadAllBytes(inputPath);
        var expected = TileWidth * TileHeight * sizeof(ushort);
        if (bytes.Length < expected)
        {
            Console.Error.WriteLine($"Expected at least {expected} bytes, got {bytes.Length}");
            return 1;
        }

        var raw = new ushort[TileWidth * TileHeight];
        Buffer.BlockCopy(bytes, 0, raw, 0, expected);

        var decoded = PanoFrameDecoder.DecodeFlatRawGrid(
            raw, TileWidth, TileHeight, palette, floorC, ceilingC, autoRange: true);
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath)) ?? ".");
        decoded.Bitmap.Save(outputPath, ImageFormat.Jpeg);
        decoded.Bitmap.Dispose();

        Console.WriteLine($"Wrote {outputPath} (window {decoded.WindowMinC:F2}..{decoded.WindowMaxC:F2} C, palette {palette})");
        return 0;
    }
}

internal static class TileJetCli
{
    public static int TryRun(string[] args)
    {
        if (args.Length == 0 || !args.Contains("--tile-jet", StringComparer.OrdinalIgnoreCase))
        {
            return -1;
        }

        var input = "";
        var output = "";
        var palette = ThermalColorPalette.Jet;
        var floorC = 20.0;
        var ceilingC = 40.0;

        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i].ToLowerInvariant())
            {
                case "--tile-jet":
                    break;
                case "--in" when i + 1 < args.Length:
                    input = args[++i];
                    break;
                case "--out" when i + 1 < args.Length:
                    output = args[++i];
                    break;
                case "--palette" when i + 1 < args.Length:
                    Enum.TryParse<ThermalColorPalette>(args[++i], ignoreCase: true, out palette);
                    break;
                case "--floor" when i + 1 < args.Length:
                    double.TryParse(args[++i], out floorC);
                    break;
                case "--ceiling" when i + 1 < args.Length:
                    double.TryParse(args[++i], out ceilingC);
                    break;
                case "--help":
                case "-h":
                    Console.WriteLine("IRPanoView.Viewer --tile-jet --in tile-u16.bin --out preview.jpg [--palette Jet]");
                    return 0;
            }
        }

        if (string.IsNullOrWhiteSpace(input) || string.IsNullOrWhiteSpace(output))
        {
            Console.Error.WriteLine("--tile-jet requires --in and --out");
            return 1;
        }

        return TileJetExporter.ConvertBinToJpeg(input, output, palette, floorC, ceilingC);
    }
}
