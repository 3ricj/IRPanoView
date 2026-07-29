using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using LibVLCSharp.Shared;

namespace IRPanoView.Viewer.Network;

/// <summary>
/// Low-latency RTSP receiver. Hardened against unavailable / hanging servers:
/// TCP preflight, short RTSP timeouts, non-blocking Stop, safe video callbacks.
/// </summary>
public sealed class RtspThermalReceiver : IDisposable
{
    private LibVLC? _libVlc;
    private MediaPlayer? _player;
    private Media? _media;
    private IntPtr _videoBuffer = IntPtr.Zero;
    private readonly object _gate = new();
    private readonly object _lifecycle = new();
    private Bitmap? _latest;
    private long _displayUs;
    private readonly Stopwatch _fpsClock = Stopwatch.StartNew();
    private int _frameCount;
    private double _fps;
    private int _drops;
    private int _videoWidth = ThermalFrameParser.DefaultPanoWidth;
    private int _videoHeight = ThermalFrameParser.DefaultPanoHeight;

    private Task? _teardownTask;

    public bool IsRunning { get; private set; }
    public double Fps => _fps;
    public int Drops => _drops;
    public string LastUrl { get; private set; } = "";
    public string? LastError { get; private set; }
    public int VideoWidth => _videoWidth;
    public int VideoHeight => _videoHeight;
    public DateTime? LastFrameAt { get; private set; }

    public bool Start(string rtspUrl, int width = 0, int height = 0)
    {
        lock (_lifecycle)
        {
            StopLocked();

            _videoWidth = width > 0 ? width : ThermalFrameParser.DefaultPanoWidth;
            _videoHeight = height > 0 ? height : ThermalFrameParser.DefaultPanoHeight;
            LastUrl = rtspUrl;
            LastError = null;
            LastFrameAt = null;
            _fps = 0;
            _drops = 0;
            _frameCount = 0;
            _fpsClock.Restart();

            if (!TryParseRtspHostPort(rtspUrl, out var host, out var port))
            {
                LastError = "Bad RTSP URL.";
                return false;
            }

            // Fail fast if the Pi/port is unreachable — do not hand VLC a hang.
            if (!TcpReachable(host, port, TimeSpan.FromSeconds(1.5)))
            {
                LastError = $"No TCP response from {host}:{port} (Pi down / wrong IP / link flap).";
                return false;
            }

            try
            {
                // Finish prior async teardown so we do not double-free / double-stop.
                _teardownTask?.Wait(TimeSpan.FromSeconds(2));
            }
            catch
            {
                // ignore
            }

            try
            {
                EnsureLibVlcInitialized();

                // Keep LibVLC ctor options minimal — unknown/module options here
                // make libvlc_new return null ("Failed to perform instanciation…").
                // RTSP / caching tweaks go on the Media below.
                _libVlc = new LibVLC(
                    "--no-audio",
                    "--no-video-title-show",
                    "--quiet");

                _media = new Media(_libVlc, rtspUrl, FromType.FromLocation);
                // Match Pi: RTSP interleaved over TCP (UDP not used for live view).
                _media.AddOption(":rtsp-tcp");
                _media.AddOption(":network-caching=0");
                _media.AddOption(":live-caching=0");
                _media.AddOption(":clock-jitter=0");
                _media.AddOption(":clock-synchro=0");
                _media.AddOption(":drop-late-frames");
                _media.AddOption(":skip-frames");
                _media.AddOption(":no-audio");

                _player = new MediaPlayer(_media);
                _player.EncounteredError += (_, _) =>
                {
                    LastError = "RTSP stream error (server closed or unreachable).";
                };

                _player.SetVideoFormat(
                    "RV32",
                    (uint)_videoWidth,
                    (uint)_videoHeight,
                    (uint)(_videoWidth * 4));
                _player.SetVideoCallbacks(Lock, Unlock, Display);

                if (!_player.Play())
                {
                    LastError = "LibVLC Play() returned false.";
                    StopLocked();
                    return false;
                }

                IsRunning = true;
                return true;
            }
            catch (Exception ex)
            {
                LastError = ex.GetType().Name + ": " + ex.Message
                    + (ex.InnerException != null ? " | " + ex.InnerException.Message : "");
                StopLocked();
                return false;
            }
        }
    }

    private static int _libInit;
    private static void EnsureLibVlcInitialized()
    {
        if (Interlocked.Exchange(ref _libInit, 1) == 1)
        {
            return;
        }

        var arch = Environment.Is64BitProcess ? "win-x64" : "win-x86";
        var libDir = Path.Combine(AppContext.BaseDirectory, "libvlc", arch);
        if (!Directory.Exists(libDir) || !File.Exists(Path.Combine(libDir, "libvlc.dll")))
        {
            throw new InvalidOperationException(
                $"LibVLC natives missing under {libDir}. Rebuild so VideoLAN.LibVLC.Windows copies libvlc/.");
        }

        Core.Initialize(libDir);
    }

    public void Stop()
    {
        lock (_lifecycle)
        {
            StopLocked();
        }
    }

    private void StopLocked()
    {
        IsRunning = false;

        var player = _player;
        var media = _media;
        var lib = _libVlc;
        var buffer = _videoBuffer;
        _player = null;
        _media = null;
        _libVlc = null;
        // Leave buffer pointer nulled here; teardown frees the captured local after
        // callbacks are cleared so in-flight Lock does not use a freed pointer.
        _videoBuffer = IntPtr.Zero;

        lock (_gate)
        {
            _latest?.Dispose();
            _latest = null;
        }

        if (player == null && media == null && lib == null && buffer == IntPtr.Zero)
        {
            return;
        }

        // Never block the UI on MediaPlayer.Stop() — it hangs when RTSP is wedged.
        var prior = _teardownTask;
        _teardownTask = Task.Run(() =>
        {
            try
            {
                prior?.Wait(TimeSpan.FromSeconds(2));
            }
            catch
            {
                // ignore
            }

            try
            {
                player?.SetVideoCallbacks(null!, null!, null!);
            }
            catch
            {
                // ignore
            }

            try
            {
                player?.Stop();
            }
            catch
            {
                // ignore
            }

            try
            {
                player?.Dispose();
            }
            catch
            {
                // ignore
            }

            try
            {
                media?.Dispose();
            }
            catch
            {
                // ignore
            }

            try
            {
                lib?.Dispose();
            }
            catch
            {
                // ignore
            }

            if (buffer != IntPtr.Zero)
            {
                // Give any in-flight Lock a beat to finish before freeing.
                Thread.Sleep(50);
                try
                {
                    Marshal.FreeHGlobal(buffer);
                }
                catch
                {
                    // ignore
                }
            }
        });
    }

    public bool TryTakeLatest(out Bitmap? bitmap, out long clientDisplayUs)
    {
        lock (_gate)
        {
            if (_latest == null)
            {
                bitmap = null;
                clientDisplayUs = 0;
                return false;
            }

            try
            {
                bitmap = (Bitmap)_latest.Clone();
                clientDisplayUs = _displayUs;
                return true;
            }
            catch
            {
                bitmap = null;
                clientDisplayUs = 0;
                return false;
            }
        }
    }

    private void Unlock(IntPtr opaque, IntPtr picture, IntPtr planes)
    {
    }

    private IntPtr Lock(IntPtr opaque, IntPtr planes)
    {
        // Always return a valid plane pointer when possible — Zero can crash VLC.
        // After StopLocked() clears _videoBuffer, allocate a throwaway so in-flight
        // callbacks do not fault during teardown.
        try
        {
            var bytes = _videoWidth * _videoHeight * 4;
            var buf = _videoBuffer;
            if (buf == IntPtr.Zero)
            {
                buf = Marshal.AllocHGlobal(bytes);
                _videoBuffer = buf;
            }

            if (planes != IntPtr.Zero)
            {
                Marshal.WriteIntPtr(planes, buf);
            }

            return buf;
        }
        catch
        {
            return IntPtr.Zero;
        }
    }

    private void Display(IntPtr opaque, IntPtr picture)
    {
        if (!IsRunning || picture == IntPtr.Zero)
        {
            return;
        }

        try
        {
            var bmp = new Bitmap(
                _videoWidth,
                _videoHeight,
                _videoWidth * 4,
                PixelFormat.Format32bppRgb,
                picture);

            var clone = (Bitmap)bmp.Clone();
            bmp.Dispose();

            lock (_gate)
            {
                if (!IsRunning)
                {
                    clone.Dispose();
                    return;
                }

                _latest?.Dispose();
                _latest = clone;
                _displayUs = Stopwatch.GetTimestamp() * 1_000_000 / Stopwatch.Frequency;
                LastFrameAt = DateTime.UtcNow;
            }

            _frameCount++;
            if (_fpsClock.Elapsed.TotalSeconds >= 1.0)
            {
                _fps = _frameCount / _fpsClock.Elapsed.TotalSeconds;
                _frameCount = 0;
                _fpsClock.Restart();
            }
        }
        catch
        {
            Interlocked.Increment(ref _drops);
        }
    }

    private static bool TryParseRtspHostPort(string url, out string host, out int port)
    {
        host = "";
        port = 8554;
        try
        {
            if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
            {
                return false;
            }

            host = uri.Host;
            port = uri.IsDefaultPort ? 8554 : uri.Port;
            return !string.IsNullOrWhiteSpace(host);
        }
        catch
        {
            return false;
        }
    }

    private static bool TcpReachable(string host, int port, TimeSpan timeout)
    {
        try
        {
            using var client = new TcpClient();
            var connect = client.ConnectAsync(host, port);
            if (!connect.Wait(timeout))
            {
                return false;
            }

            return client.Connected;
        }
        catch
        {
            return false;
        }
    }

    public void Dispose()
    {
        Stop();
        try
        {
            _teardownTask?.Wait(TimeSpan.FromSeconds(3));
        }
        catch
        {
            // ignore
        }
    }
}
