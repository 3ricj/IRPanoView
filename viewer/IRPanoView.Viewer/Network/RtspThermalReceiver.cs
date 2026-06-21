using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using LibVLCSharp.Shared;

namespace IRPanoView.Viewer.Network;

public sealed class RtspThermalReceiver : IDisposable
{
    private LibVLC? _libVlc;
    private MediaPlayer? _player;
    private Media? _media;
    private IntPtr _videoBuffer = IntPtr.Zero;
    private readonly object _gate = new();
    private Bitmap? _latest;
    private long _displayUs;
    private readonly Stopwatch _fpsClock = Stopwatch.StartNew();
    private int _frameCount;
    private double _fps;
    private int _drops;
    private int _videoWidth = ThermalFrameParser.DefaultPanoWidth;
    private int _videoHeight = ThermalFrameParser.DefaultPanoHeight;

    public bool IsRunning { get; private set; }
    public double Fps => _fps;
    public int Drops => _drops;
    public string LastUrl { get; private set; } = "";
    public int VideoWidth => _videoWidth;
    public int VideoHeight => _videoHeight;

    public bool Start(string rtspUrl, int width = 0, int height = 0)
    {
        Stop();
        _videoWidth = width > 0 ? width : ThermalFrameParser.DefaultPanoWidth;
        _videoHeight = height > 0 ? height : ThermalFrameParser.DefaultPanoHeight;

        Core.Initialize();

        _libVlc = new LibVLC(
            "--no-audio",
            "--network-caching=0",
            "--live-caching=0",
            "--clock-jitter=0",
            "--clock-synchro=0");

        _media = new Media(_libVlc, rtspUrl, FromType.FromLocation);
        _player = new MediaPlayer(_media);
        _player.SetVideoFormat(
            "RV32",
            (uint)_videoWidth,
            (uint)_videoHeight,
            (uint)(_videoWidth * 4));
        _player.SetVideoCallbacks(Lock, Unlock, Display);
        _player.Play();

        LastUrl = rtspUrl;
        IsRunning = true;
        return true;
    }

    public void Stop()
    {
        if (_player != null)
        {
            _player.Stop();
            _player.SetVideoCallbacks(null!, null!, null!);
        }

        _media?.Dispose();
        _player?.Dispose();
        _libVlc?.Dispose();
        _media = null;
        _player = null;
        _libVlc = null;

        if (_videoBuffer != IntPtr.Zero)
        {
            Marshal.FreeHGlobal(_videoBuffer);
            _videoBuffer = IntPtr.Zero;
        }

        lock (_gate)
        {
            _latest?.Dispose();
            _latest = null;
        }

        IsRunning = false;
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

            bitmap = (Bitmap)_latest.Clone();
            clientDisplayUs = _displayUs;
            return true;
        }
    }

    private void Unlock(IntPtr opaque, IntPtr picture, IntPtr planes)
    {
    }

    private IntPtr Lock(IntPtr opaque, IntPtr planes)
    {
        var bytes = _videoWidth * _videoHeight * 4;
        if (_videoBuffer == IntPtr.Zero || bytes != _videoWidth * _videoHeight * 4)
        {
            if (_videoBuffer != IntPtr.Zero)
            {
                Marshal.FreeHGlobal(_videoBuffer);
            }
            _videoBuffer = Marshal.AllocHGlobal(bytes);
        }

        Marshal.WriteIntPtr(planes, _videoBuffer);
        return _videoBuffer;
    }

    private void Display(IntPtr opaque, IntPtr picture)
    {
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
                _latest?.Dispose();
                _latest = clone;
                _displayUs = Stopwatch.GetTimestamp() * 1_000_000 / Stopwatch.Frequency;
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

    public void Dispose()
    {
        Stop();
    }
}
