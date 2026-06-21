using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Net.Sockets;
using IRPanoView.Viewer.Display;
using IRPanoView.Viewer.Network;

const int port = 8765;
const int waitSeconds = 15;

Console.WriteLine($"Waiting up to {waitSeconds}s for IRPV UDP on port {port}...");

using var client = new UdpClient();
client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
client.Client.Bind(new IPEndPoint(IPAddress.Any, port));

var assembler = new ThermalChunkReassembler();
var deadline = DateTime.UtcNow.AddSeconds(waitSeconds);
ThermalFrameParser.ParsedFrame? last = null;
var count = 0;

while (DateTime.UtcNow < deadline)
{
    if (!client.Client.Poll(200_000, SelectMode.SelectRead))
    {
        continue;
    }

    var remote = new IPEndPoint(IPAddress.Any, 0);
    var packet = client.Receive(ref remote);
    ThermalFrameParser.ParsedFrame? parsed = null;
    if (packet.Length >= ThermalFrameParser.HeaderBytes
        && BitConverter.ToUInt32(packet.AsSpan(0, 4)) == ThermalFrameParser.Magic)
    {
        parsed = ThermalFrameParser.Parse(packet);
    }
    else
    {
        var assembled = assembler.Push(packet);
        if (assembled != null)
        {
            parsed = ThermalFrameParser.Parse(assembled);
        }
    }

    if (parsed == null)
    {
        continue;
    }

    count++;
    last = parsed;
    Console.WriteLine($"  frame {count}: seq={parsed.Sequence} {parsed.Width}x{parsed.Height} from {remote}");
    if (count >= 5)
    {
        break;
    }
}

if (last == null)
{
    Console.Error.WriteLine("No frames received.");
    return 1;
}

var outPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "capture.png"));
var decoded = PanoFrameDecoder.DecodeFlatRawGrid(
    last.RawPixels,
    last.Width,
    last.Height,
    ThermalColorPalette.Jet,
    20,
    40);
decoded.Bitmap.Save(outPath, ImageFormat.Png);
decoded.Bitmap.Dispose();

Console.WriteLine($"Saved preview: {outPath}");
Console.WriteLine($"Window: {decoded.WindowMinC:F1} .. {decoded.WindowMaxC:F1} C");
return 0;
