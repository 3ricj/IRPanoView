using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace IRPanoView.Viewer.Network;

public sealed class MetaStreamReceiver : IDisposable
{
    public const uint Magic = 0x4d505249; // "IRPM"
    public const int PacketBytes = 76;

    public sealed class MetaPacket
    {
        public required uint VideoSeq { get; init; }
        public required long ComposeUs { get; init; }
        public required long EncodeSubmitUs { get; init; }
        public required uint[] SlotFrameSeq { get; init; }
        public required long[] SlotUsbFrameUs { get; init; }
        public required long ClientReceiveUs { get; init; }
    }

    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private Task? _task;
    private readonly object _gate = new();
    private MetaPacket? _latest;

    public bool IsRunning { get; private set; }

    public bool Start(int port)
    {
        Stop();
        _udp = new UdpClient(port);
        _cts = new CancellationTokenSource();
        _task = Task.Run(() => RunAsync(_cts.Token));
        IsRunning = true;
        return true;
    }

    public void Stop()
    {
        _cts?.Cancel();
        _udp?.Close();
        try
        {
            _task?.Wait(TimeSpan.FromSeconds(1));
        }
        catch
        {
            // ignore
        }

        _cts?.Dispose();
        _udp = null;
        _task = null;
        _cts = null;
        IsRunning = false;
    }

    public bool TryTakeLatest(out MetaPacket? packet)
    {
        lock (_gate)
        {
            packet = _latest;
            _latest = null;
            return packet != null;
        }
    }

    public bool TryPeekLatest(out MetaPacket? packet)
    {
        lock (_gate)
        {
            packet = _latest;
            return packet != null;
        }
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && _udp != null)
        {
            try
            {
                var result = await _udp.ReceiveAsync(cancellationToken);
                if (result.Buffer.Length < PacketBytes)
                {
                    continue;
                }

                var buffer = result.Buffer;
                if (buffer.Length < PacketBytes)
                {
                    continue;
                }

                if (BitConverter.ToUInt32(buffer, 0) != Magic)
                {
                    continue;
                }

                var packet = new MetaPacket
                {
                    VideoSeq = BitConverter.ToUInt32(buffer, 8),
                    ComposeUs = BitConverter.ToInt64(buffer, 12),
                    EncodeSubmitUs = BitConverter.ToInt64(buffer, 20),
                    SlotFrameSeq =
                    [
                        BitConverter.ToUInt32(buffer, 28),
                        BitConverter.ToUInt32(buffer, 32),
                        BitConverter.ToUInt32(buffer, 36),
                        BitConverter.ToUInt32(buffer, 40),
                    ],
                    SlotUsbFrameUs =
                    [
                        BitConverter.ToInt64(buffer, 44),
                        BitConverter.ToInt64(buffer, 52),
                        BitConverter.ToInt64(buffer, 60),
                        BitConverter.ToInt64(buffer, 68),
                    ],
                    ClientReceiveUs = Stopwatch.GetTimestamp() * 1_000_000 / Stopwatch.Frequency,
                };

                lock (_gate)
                {
                    _latest = packet;
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch
            {
                await Task.Delay(50, cancellationToken);
            }
        }
    }

    public void Dispose()
    {
        Stop();
    }
}
