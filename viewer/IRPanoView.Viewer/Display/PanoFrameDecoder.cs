using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace IRPanoView.Viewer.Display;

public static class PanoFrameDecoder
{
    public sealed class DecodeResult
    {
        public required Bitmap Bitmap { get; init; }
        public required double WindowMinC { get; init; }
        public required double WindowMaxC { get; init; }
    }

    public static DecodeResult DecodeFlatRawGrid(
        ReadOnlySpan<ushort> rawPixels,
        int width,
        int height,
        ThermalColorPalette palette,
        double floorC,
        double ceilingC,
        bool autoRange = true)
    {
        if (rawPixels.Length < width * height)
        {
            throw new ArgumentException("rawPixels too small for dimensions");
        }

        var samples = new List<double>(rawPixels.Length / 4);
        for (var i = 0; i < rawPixels.Length; i += 4)
        {
            samples.Add(HikTherm.CelsiusFromRawU16(rawPixels[i]));
        }

        var sorted = samples.Count == 0 ? new List<double> { floorC, ceilingC } : samples.OrderBy(v => v).ToList();
        var (winMin, winMax) = DisplayWindow.Resolve(sorted, floorC, ceilingC, autoRange);

        var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        var rect = new Rectangle(0, 0, width, height);
        var data = bitmap.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
        try
        {
            var stride = data.Stride;
            var buffer = new byte[stride * height];
            var i = 0;
            for (var y = 0; y < height; y++)
            {
                var row = y * stride;
                for (var x = 0; x < width; x++)
                {
                    var raw = rawPixels[i++];
                    var c = HikTherm.CelsiusFromRawU16(raw);
                    var d = DisplayRange.CelsiusToDisplay(c, winMin, winMax);
                    var argb = ThermalColormap.Color(palette, d);
                    var offset = row + x * 4;
                    buffer[offset] = (byte)(argb & 0xFF);
                    buffer[offset + 1] = (byte)((argb >> 8) & 0xFF);
                    buffer[offset + 2] = (byte)((argb >> 16) & 0xFF);
                    buffer[offset + 3] = 0xFF;
                }
            }

            Marshal.Copy(buffer, 0, data.Scan0, buffer.Length);
        }
        finally
        {
            bitmap.UnlockBits(data);
        }

        return new DecodeResult
        {
            Bitmap = bitmap,
            WindowMinC = winMin,
            WindowMaxC = winMax,
        };
    }
}
