using System.Drawing;
using IRPanoView.Viewer.Display;
using IRPanoView.Viewer.Network;

namespace IRPanoView.Viewer;

internal sealed class MainForm : Form
{
    private readonly RawCameraStreamClient _stream = new();
    private readonly System.Windows.Forms.Timer _renderTimer = new() { Interval = 100 };

    private readonly PictureBox _picture = new() { Dock = DockStyle.Fill, BackColor = Color.Black, SizeMode = PictureBoxSizeMode.Zoom };
    private readonly Label _stats = new() { Dock = DockStyle.Fill, AutoSize = false, TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(8, 4, 8, 4) };
    private readonly TextBox _piHost = new() { Width = 140, Text = "192.168.8.115", Anchor = AnchorStyles.Left };
    private readonly NumericUpDown _rawPort = new() { Minimum = 1, Maximum = 65535, Value = 8767, Width = 70, Anchor = AnchorStyles.Left };
    private readonly Button _connect = new() { Text = "Connect", AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, MinimumSize = new Size(90, 28), Padding = new Padding(8, 0, 8, 0) };
    private readonly Button _record = new() { Text = "Record raw", AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, MinimumSize = new Size(100, 28), Padding = new Padding(8, 0, 8, 0), Enabled = false };

    private Bitmap? _currentBitmap;
    private readonly long[] _lastCounts = new long[RawCameraStreamClient.CameraCount + 1];
    private DateTime _lastFpsAt = DateTime.UtcNow;
    private readonly double[] _fps = new double[RawCameraStreamClient.CameraCount + 1];

    public MainForm()
    {
        Text = "IRPanoView Raw Camera Viewer";
        Width = 1280;
        Height = 460;
        MinimumSize = new Size(720, 320);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;

        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(8, 8, 8, 8),
            WrapContents = true,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
        };

        _connect.Click += (_, _) => ToggleConnect();
        _record.Click += (_, _) => ToggleRecord();
        _renderTimer.Tick += (_, _) => RenderFrame();
        FormClosing += (_, _) => Cleanup();

        toolbar.Controls.Add(FieldLabel("Pi host"));
        toolbar.Controls.Add(_piHost);
        toolbar.Controls.Add(FieldLabel("Raw TCP"));
        toolbar.Controls.Add(_rawPort);
        toolbar.Controls.Add(_connect);
        toolbar.Controls.Add(_record);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 56f));
        root.Controls.Add(toolbar, 0, 0);
        root.Controls.Add(_picture, 0, 1);
        root.Controls.Add(_stats, 0, 2);

        Controls.Add(root);

        _stats.Text = "Connect to the Pi raw stream (TCP 8767). Shows 4 raw per-camera tiles (Jet). Record raw saves per-camera files for post-stitching.";
    }

    private static Label FieldLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        TextAlign = ContentAlignment.MiddleLeft,
        Anchor = AnchorStyles.Left,
        Margin = new Padding(8, 6, 2, 0),
    };

    private void ToggleConnect()
    {
        if (_stream.IsRunning)
        {
            _renderTimer.Stop();
            _stream.Stop();
        }
        else
        {
            _stream.Configure(_piHost.Text.Trim(), (int)_rawPort.Value);
            _stream.Start();
            _renderTimer.Start();
        }

        UpdateButtons();
    }

    private void ToggleRecord()
    {
        if (_stream.IsRecording)
        {
            _stream.StopRecording();
        }
        else
        {
            using var dlg = new FolderBrowserDialog
            {
                Description = "Choose raw capture output folder",
            };
            if (dlg.ShowDialog() != DialogResult.OK)
            {
                return;
            }

            var sessionDir = Path.Combine(
                dlg.SelectedPath,
                "rawcam-" + DateTime.Now.ToString("yyyyMMdd-HHmmss"));
            _stream.StartRecording(sessionDir);
        }

        UpdateButtons();
    }

    private void UpdateButtons()
    {
        _connect.Text = _stream.IsRunning ? "Disconnect" : "Connect";
        _record.Text = _stream.IsRecording ? "Stop record" : "Record raw";
        _piHost.Enabled = !_stream.IsRunning;
        _rawPort.Enabled = !_stream.IsRunning;
        _record.Enabled = _stream.IsRunning;
    }

    private void RenderFrame()
    {
        if (!_stream.IsRunning)
        {
            _stats.Text = "Disconnected.";
            return;
        }

        var latest = _stream.SnapshotLatest();
        var bitmap = CameraQuadRenderer.Render(latest, ThermalColorPalette.Jet, 20.0, 40.0);
        ReplaceBitmap(bitmap);

        UpdateFps();

        var parts = new List<string>();
        for (var slot = 1; slot <= RawCameraStreamClient.CameraCount; slot++)
        {
            parts.Add($"cam{slot}={_stream.FrameCount(slot)} ({_fps[slot]:F1}/s)");
        }

        var rec = _stream.IsRecording ? $"  recording={_stream.RecordWritten}" : "";
        var err = _stream.LastError != null ? $"  err={_stream.LastError}" : "";
        _stats.Text = string.Join("  ", parts) + rec + err;
    }

    private void UpdateFps()
    {
        var now = DateTime.UtcNow;
        var elapsed = (now - _lastFpsAt).TotalSeconds;
        if (elapsed < 1.0)
        {
            return;
        }

        for (var slot = 1; slot <= RawCameraStreamClient.CameraCount; slot++)
        {
            var c = _stream.FrameCount(slot);
            _fps[slot] = (c - _lastCounts[slot]) / elapsed;
            _lastCounts[slot] = c;
        }

        _lastFpsAt = now;
    }

    private void ReplaceBitmap(Bitmap bitmap)
    {
        var old = _currentBitmap;
        _currentBitmap = bitmap;
        _picture.Image = _currentBitmap;
        old?.Dispose();
    }

    private void Cleanup()
    {
        _renderTimer.Stop();
        _stream.Dispose();
        _currentBitmap?.Dispose();
    }
}
