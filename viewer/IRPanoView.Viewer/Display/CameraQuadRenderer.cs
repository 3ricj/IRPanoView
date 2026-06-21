using System.Drawing;
using IRPanoView.Viewer.Network;

namespace IRPanoView.Viewer.Display;

/// <summary>
/// Renders the 4 raw per-camera tiles into a single horizontal strip (slot 1..4,
/// left to right) using a Jet palette and a shared display window so brightness
/// is consistent across cameras and doesn't flicker between them.
/// </summary>
public static class CameraQuadRenderer
{
    private const int Gap = 4;

    public static Bitmap Render(
        RawCameraStreamClient.CameraFrame?[] latest,
        ThermalColorPalette palette,
        double floorC,
        double ceilingC)
    {
        // Determine tile geometry from the first available frame (256x192 expected).
        var tileW = 256;
        var tileH = 192;
        foreach (var f in latest)
        {
            if (f != null)
            {
                tileW = f.Parsed.Width;
                tileH = f.Parsed.Height;
                break;
            }
        }

        // Shared window across all valid tiles.
        var samples = new List<double>();
        foreach (var f in latest)
        {
            if (f == null)
            {
                continue;
            }

            var px = f.Parsed.RawPixels;
            for (var i = 0; i < px.Length; i += 8)
            {
                samples.Add(HikTherm.CelsiusFromRawU16(px[i]));
            }
        }

        double winMin = floorC;
        double winMax = ceilingC;
        if (samples.Count > 0)
        {
            samples.Sort();
            (winMin, winMax) = DisplayWindow.Resolve(samples, floorC, ceilingC, autoRange: true);
        }

        var count = RawCameraStreamClient.CameraCount;
        var totalW = count * tileW + (count - 1) * Gap;
        var composed = new Bitmap(totalW, tileH);
        using var g = Graphics.FromImage(composed);
        g.Clear(Color.FromArgb(20, 20, 20));
        using var font = new Font("Segoe UI", 9f, FontStyle.Bold);

        for (var slot = 1; slot <= count; slot++)
        {
            var x = (slot - 1) * (tileW + Gap);
            var frame = latest[slot];
            if (frame == null)
            {
                using var labelBrushMissing = new SolidBrush(Color.Gray);
                g.DrawString($"cam{slot}: no frame", font, labelBrushMissing, x + 6, 6);
                continue;
            }

            var decoded = PanoFrameDecoder.DecodeFlatRawGrid(
                frame.Parsed.RawPixels,
                frame.Parsed.Width,
                frame.Parsed.Height,
                palette,
                winMin,
                winMax,
                autoRange: false);
            g.DrawImage(decoded.Bitmap, x, 0, tileW, tileH);
            decoded.Bitmap.Dispose();

            var serial = frame.Parsed.CameraSerial ?? "?";
            using var labelBrush = new SolidBrush(Color.White);
            using var shadow = new SolidBrush(Color.FromArgb(160, 0, 0, 0));
            g.FillRectangle(shadow, x, 0, tileW, 18);
            g.DrawString($"cam{slot}  {serial}", font, labelBrush, x + 4, 2);
        }

        return composed;
    }
}
