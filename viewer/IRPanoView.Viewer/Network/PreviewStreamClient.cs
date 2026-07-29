using System.Buffers.Binary;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using IRPanoView.Viewer.Display;

namespace IRPanoView.Viewer.Network;

/// <summary>
/// Low-latency live preview: IRPR-framed IRP8 packets with windowed u8 pixels.
/// Client applies Jet (no LibVLC / no decode).
/// </summary>
public sealed class PreviewStreamClient : IDisposable
{
    public const uint PacketMagic = 0x38505249; // "IRP8"
    public const int HeaderBytes = 28;

    private readonly object _gate = new();
    private CancellationTokenSource? _cts;
    private Task? _task;
    private string _host = "10.254.254.1";
    private int _port = 8769;

    private Bitmap? _latest;
    private long _frameCount;
    private double _fps;
    private readonly Stopwatch _fpsClock = Stopwatch.StartNew();
    private int _fpsFrames;
    private float _minC;
    private float _maxC;
    private int _width;
    private int _height;

    public bool IsRunning { get; private set; }
    public string? LastError { get; private set; }
    public long FrameCount => Interlocked.Read(ref _frameCount);
    public double Fps => _fps;
    public int Width => _width;
    public int Height => _height;
    public float MinC => _minC;
    public float MaxC => _maxC;
    public DateTime? LastFrameAt { get; private set; }

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
            _task?.Wait(TimeSpan.FromSeconds(2));
        }
        catch
        {
            // ignore
        }

        lock (_gate)
        {
            _cts?.Dispose();
            _cts = null;
            _task = null;
            IsRunning = false;
            _latest?.Dispose();
            _latest = null;
        }
    }

    public bool TryTakeLatest(out Bitmap? bitmap)
    {
        lock (_gate)
        {
            if (_latest == null)
            {
                bitmap = null;
                return false;
            }

            try
            {
                bitmap = (Bitmap)_latest.Clone();
                return true;
            }
            catch
            {
                bitmap = null;
                return false;
            }
        }
    }

    private async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                using var client = new System.Net.Sockets.TcpClient();
                client.NoDelay = true;
                await client.ConnectAsync(_host, _port, cancellationToken);
                using var stream = client.GetStream();
                var reader = new RawTcpFramingReader(stream);
                LastError = null;

                while (!cancellationToken.IsCancellationRequested)
                {
                    var packet = await reader.ReadPacketAsync(cancellationToken);
                    if (packet == null)
                    {
                        break;
                    }

                    if (!TryRenderPacket(packet))
                    {
                        continue;
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
                    await Task.Delay(500, cancellationToken);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }
    }

    private bool TryRenderPacket(byte[] packet)
    {
        if (packet.Length < HeaderBytes)
        {
            return false;
        }

        var magic = BinaryPrimitives.ReadUInt32LittleEndian(packet.AsSpan(0, 4));
        if (magic != PacketMagic)
        {
            LastError = $"Bad preview magic 0x{magic:X8}";
            return false;
        }

        var width = BinaryPrimitives.ReadUInt16LittleEndian(packet.AsSpan(4, 2));
        var height = BinaryPrimitives.ReadUInt16LittleEndian(packet.AsSpan(6, 2));
        var minC = BinaryPrimitives.ReadSingleLittleEndian(packet.AsSpan(20, 4));
        var maxC = BinaryPrimitives.ReadSingleLittleEndian(packet.AsSpan(24, 4));
        var pixelsNeeded = width * height;
        if (width == 0 || height == 0 || packet.Length < HeaderBytes + pixelsNeeded)
        {
            return false;
        }

        // Precompute Jet as BGRA ints once; bulk fill ~145k px (was the ~13 fps bottleneck).
        var lut = JetBgraLut;
        var bmp = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        var data = bmp.LockBits(
            new Rectangle(0, 0, width, height),
            ImageLockMode.WriteOnly,
            PixelFormat.Format32bppArgb);
        try
        {
            unsafe
            {
                var dst = (int*)data.Scan0;
                var strideInts = data.Stride / 4;
                fixed (byte* srcBase = packet)
                {
                    var src = srcBase + HeaderBytes;
                    for (var y = 0; y < height; y++)
                    {
                        var row = dst + y * strideInts;
                        var s = src + y * width;
                        for (var x = 0; x < width; x++)
                        {
                            row[x] = lut[s[x]];
                        }
                    }
                }
            }
        }
        finally
        {
            bmp.UnlockBits(data);
        }

        lock (_gate)
        {
            _latest?.Dispose();
            _latest = bmp;
            _width = width;
            _height = height;
            _minC = minC;
            _maxC = maxC;
            LastFrameAt = DateTime.UtcNow;
        }

        Interlocked.Increment(ref _frameCount);
        _fpsFrames++;
        if (_fpsClock.Elapsed.TotalSeconds >= 1.0)
        {
            _fps = _fpsFrames / _fpsClock.Elapsed.TotalSeconds;
            _fpsFrames = 0;
            _fpsClock.Restart();
        }

        return true;
    }

    private static readonly int[] JetBgraLut = BuildJetBgraLut();

    private static int[] BuildJetBgraLut()
    {
        var lut = new int[256];
        for (var i = 0; i < 256; i++)
        {
            var argb = ThermalColormap.Color(ThermalColorPalette.Jet, i);
            // Format32bppArgb memory order is B,G,R,A == little-endian int 0xAARRGGBB.
            lut[i] = argb;
        }

        return lut;
    }

    public void Dispose()
    {
        Stop();
    }
}
