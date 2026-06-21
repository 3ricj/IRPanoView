using System.Net;
using System.Net.Sockets;
using IRPanoView.Viewer.Network;

namespace IRPanoView.Viewer.Network;

public sealed class ThermalStreamReceiver : IDisposable
{
    public sealed class StreamStats
    {
        public double Fps { get; set; }
        public int LastSequence { get; set; }
        public long Drops { get; set; }
        public string? LastSource { get; set; }
    }

    private readonly object _gate = new();
    private readonly ThermalChunkReassembler _chunkAssembler = new();
    private UdpClient? _client;
    private CancellationTokenSource? _cts;
    private Task? _task;
    private ThermalFrameParser.ParsedFrame? _latest;
    private int _lastSequence = -1;
    private int _frames;
    private long _drops;
    private DateTime _fpsWindowStart = DateTime.UtcNow;
    private double _fps;

    public StreamStats Stats
    {
        get
        {
            lock (_gate)
            {
                return new StreamStats
                {
                    Fps = _fps,
                    LastSequence = _lastSequence,
                    Drops = _drops,
                    LastSource = _latestSource,
                };
            }
        }
    }

    private string? _latestSource;

    public bool IsRunning => _task is { IsCompleted: false };

    public void Start(int port = 8765)
    {
        Stop();
        _cts = new CancellationTokenSource();
        _client = new UdpClient
        {
            Client =
            {
                ExclusiveAddressUse = false,
            },
        };
        _client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _client.Client.Bind(new IPEndPoint(IPAddress.Any, port));
        _task = Task.Run(() => ReceiveLoop(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        try
        {
            _task?.Wait(500);
        }
        catch (AggregateException)
        {
        }

        _client?.Dispose();
        _client = null;
        _cts?.Dispose();
        _cts = null;
        _task = null;
    }

    public bool TryTakeLatest(out ThermalFrameParser.ParsedFrame? frame)
    {
        lock (_gate)
        {
            frame = _latest;
            return frame != null;
        }
    }

    private void ReceiveLoop(CancellationToken token)
    {
        var client = _client ?? throw new InvalidOperationException("UDP client not started");
        while (!token.IsCancellationRequested)
        {
            try
            {
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
                    var assembled = _chunkAssembler.Push(packet);
                    if (assembled != null)
                    {
                        parsed = ThermalFrameParser.Parse(assembled);
                    }
                }

                if (parsed == null)
                {
                    continue;
                }

                lock (_gate)
                {
                    if (_lastSequence >= 0 && parsed.Sequence <= _lastSequence)
                    {
                        _drops++;
                    }
                    else if (_lastSequence >= 0 && parsed.Sequence > _lastSequence + 1)
                    {
                        _drops += parsed.Sequence - _lastSequence - 1;
                    }

                    _lastSequence = parsed.Sequence;
                    _latest = parsed;
                    _latestSource = remote.ToString();
                    _frames++;

                    var elapsed = (DateTime.UtcNow - _fpsWindowStart).TotalSeconds;
                    if (elapsed >= 1.0)
                    {
                        _fps = _frames / elapsed;
                        _frames = 0;
                        _fpsWindowStart = DateTime.UtcNow;
                    }
                }
            }
            catch (SocketException) when (token.IsCancellationRequested)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
        }
    }

    public void Dispose() => Stop();
}
