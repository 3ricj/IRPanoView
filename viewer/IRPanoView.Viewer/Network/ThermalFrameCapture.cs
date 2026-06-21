using System.Net;
using System.Net.Sockets;
using System.Text;

namespace IRPanoView.Viewer.Network;

public sealed class ThermalFrameCapture
{
    public sealed class CaptureResult
    {
        public required ThermalFrameParser.ParsedFrame Frame { get; init; }
        public required byte[] RawPacket { get; init; }
        public required string Source { get; init; }
        public required int DatagramCount { get; init; }
        public required int ChunkDatagramCount { get; init; }
        public required IReadOnlyList<string> DatagramLog { get; init; }
    }

    public static CaptureResult? CaptureOne(int port, TimeSpan timeout)
    {
        using var client = new UdpClient();
        client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        client.Client.Bind(new IPEndPoint(IPAddress.Any, port));
        client.Client.ReceiveTimeout = 200;

        var assembler = new ThermalChunkReassembler();
        var log = new List<string>();
        var datagrams = 0;
        var chunkDatagrams = 0;
        var deadline = DateTime.UtcNow + timeout;

        while (DateTime.UtcNow < deadline)
        {
            try
            {
                var remote = new IPEndPoint(IPAddress.Any, 0);
                var packet = client.Receive(ref remote);
                datagrams++;

                var magic = packet.Length >= 4 ? BitConverter.ToUInt32(packet.AsSpan(0, 4)) : 0u;
                var magicLabel = magic switch
                {
                    ThermalFrameParser.Magic => "IRPV",
                    ThermalChunkReassembler.Magic => "IRPC",
                    _ => $"0x{magic:X8}",
                };
                log.Add($"{remote} len={packet.Length} magic={magicLabel}");

                byte[]? raw = null;
                ThermalFrameParser.ParsedFrame? parsed = null;

                if (magic == ThermalFrameParser.Magic)
                {
                    raw = packet;
                    parsed = ThermalFrameParser.Parse(packet);
                }
                else if (magic == ThermalChunkReassembler.Magic)
                {
                    chunkDatagrams++;
                    raw = assembler.Push(packet);
                    if (raw != null)
                    {
                        parsed = ThermalFrameParser.Parse(raw);
                    }
                }

                if (parsed == null || raw == null)
                {
                    continue;
                }

                return new CaptureResult
                {
                    Frame = parsed,
                    RawPacket = raw.ToArray(),
                    Source = remote.ToString(),
                    DatagramCount = datagrams,
                    ChunkDatagramCount = chunkDatagrams,
                    DatagramLog = log,
                };
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.TimedOut)
            {
            }
        }

        return null;
    }
}
