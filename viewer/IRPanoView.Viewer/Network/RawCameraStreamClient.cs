using System.Text.Json;
using IRPanoView.Viewer.Network;

namespace IRPanoView.Viewer.Network;

/// <summary>
/// Single persistent TCP client for the Pi raw stream (port 8767). The Pi now
/// emits one tagged IRPV frame per camera per tick (256x192, unstitched). This
/// client routes frames by camera slot for a live quad view and, when enabled,
/// records each camera's frames to its own folder for post-process stitching.
/// </summary>
public sealed class RawCameraStreamClient : IDisposable
{
    public const int CameraCount = 4;

    public sealed class CameraFrame
    {
        public required ThermalFrameParser.ParsedFrame Parsed { get; init; }
        public required byte[] RawPacket { get; init; }
        public required DateTime ReceivedAt { get; init; }
    }

    private readonly object _gate = new();
    private readonly CameraFrame?[] _latest = new CameraFrame?[CameraCount + 1]; // index by slot 1..4
    private readonly long[] _frameCount = new long[CameraCount + 1];

    private CancellationTokenSource? _cts;
    private Task? _task;
    private string _host = "192.168.8.115";
    private int _port = 8767;

    private string? _recordDir;
    private long _recordWritten;

    public bool IsRunning { get; private set; }
    public bool IsRecording => _recordDir != null;
    public long RecordWritten => Interlocked.Read(ref _recordWritten);
    public string? LastError { get; private set; }

    public void Configure(string host, int port)
    {
        _host = host;
        _port = port;
    }

    public void Start()
    {
        lock (_gate)
        {
            if (IsRunning)
            {
                return;
            }

            LastError = null;
            _cts = new CancellationTokenSource();
            _task = Task.Run(() => RunAsync(_cts.Token));
            IsRunning = true;
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            if (!IsRunning)
            {
                return;
            }

            _cts?.Cancel();
        }

        try
        {
            _task?.Wait(TimeSpan.FromSeconds(3));
        }
        catch
        {
            // ignore shutdown races
        }

        lock (_gate)
        {
            _cts?.Dispose();
            _cts = null;
            _task = null;
            IsRunning = false;
            _recordDir = null;
        }
    }

    public bool StartRecording(string sessionDir)
    {
        Directory.CreateDirectory(sessionDir);
        Interlocked.Exchange(ref _recordWritten, 0);
        lock (_gate)
        {
            _recordDir = sessionDir;
        }

        return true;
    }

    public void StopRecording()
    {
        lock (_gate)
        {
            _recordDir = null;
        }
    }

    /// <summary>Returns the latest frame per slot (index 1..4), or null if none yet.</summary>
    public CameraFrame?[] SnapshotLatest()
    {
        lock (_gate)
        {
            return (CameraFrame?[])_latest.Clone();
        }
    }

    public long FrameCount(int slot)
    {
        lock (_gate)
        {
            return slot >= 0 && slot < _frameCount.Length ? _frameCount[slot] : 0;
        }
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                using var client = new System.Net.Sockets.TcpClient();
                await client.ConnectAsync(_host, _port, cancellationToken);
                using var stream = client.GetStream();
                var reader = new RawTcpFramingReader(stream);

                while (!cancellationToken.IsCancellationRequested)
                {
                    var packet = await reader.ReadPacketAsync(cancellationToken);
                    if (packet == null)
                    {
                        break;
                    }

                    var parsed = ThermalFrameParser.Parse(packet);
                    if (parsed == null)
                    {
                        continue;
                    }

                    var slot = parsed.CameraSlot ?? 0;
                    if (slot < 1 || slot > CameraCount)
                    {
                        continue;
                    }

                    var frame = new CameraFrame
                    {
                        Parsed = parsed,
                        RawPacket = packet,
                        ReceivedAt = DateTime.Now,
                    };

                    string? recordDir;
                    lock (_gate)
                    {
                        _latest[slot] = frame;
                        _frameCount[slot]++;
                        recordDir = _recordDir;
                    }

                    if (recordDir != null)
                    {
                        await RecordFrameAsync(recordDir, frame, cancellationToken);
                    }
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                LastError = ex.Message;
                try
                {
                    await Task.Delay(1000, cancellationToken);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }
    }

    private async Task RecordFrameAsync(string sessionDir, CameraFrame frame, CancellationToken cancellationToken)
    {
        var parsed = frame.Parsed;
        var slot = parsed.CameraSlot ?? 0;
        var serial = string.IsNullOrWhiteSpace(parsed.CameraSerial) ? "unknown" : parsed.CameraSerial!;
        var safeSerial = string.Concat(serial.Where(c => char.IsLetterOrDigit(c) || c is '-' or '_'));
        var camDir = Path.Combine(sessionDir, $"cam{slot}_{safeSerial}");
        Directory.CreateDirectory(camDir);

        // Group sequence (shared across the 4 cameras of one tick) keeps the
        // per-camera folders aligned by filename for post stitching.
        var groupSeq = parsed.Meta?.EmitSeq ?? (uint)parsed.Sequence;
        var stem = $"frame-{groupSeq:D6}";

        // .irpv = metadata-only sidecar (header + MetaV2 + CameraInfo). Computed
        // from the metadata layout (NOT packet length) since the wire payload may
        // be deflated. Pixels are stored once, uncompressed, in -u16.bin.
        var metaLen = ThermalFrameParser.HeaderBytes
            + (parsed.Meta != null ? ThermalFrameParser.MetaV2Bytes : 0)
            + (parsed.CameraSlot != null ? ThermalFrameParser.CameraInfoBytes : 0);
        metaLen = Math.Min(metaLen, frame.RawPacket.Length);
        var meta = new byte[metaLen];
        Buffer.BlockCopy(frame.RawPacket, 0, meta, 0, metaLen);
        await File.WriteAllBytesAsync(Path.Combine(camDir, stem + ".irpv"), meta, cancellationToken);

        var payloadBytes = parsed.RawPixels.Length * 2;
        var payload = new byte[payloadBytes];
        Buffer.BlockCopy(parsed.RawPixels, 0, payload, 0, payloadBytes);
        await File.WriteAllBytesAsync(Path.Combine(camDir, stem + "-u16.bin"), payload, cancellationToken);

        var line = JsonSerializer.Serialize(new
        {
            group_seq = groupSeq,
            slot,
            serial,
            width = parsed.Width,
            height = parsed.Height,
            parsed.Sequence,
            parsed.TimestampUs,
            compose_us = parsed.Meta?.ComposeUs,
            emit_us = parsed.Meta?.EmitUs,
            received_local = frame.ReceivedAt.ToString("o"),
        });
        await File.AppendAllTextAsync(
            Path.Combine(sessionDir, "index.jsonl"),
            line + Environment.NewLine,
            cancellationToken);

        Interlocked.Increment(ref _recordWritten);
    }

    public void Dispose()
    {
        Stop();
    }
}
