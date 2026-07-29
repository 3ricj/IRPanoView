using System.Drawing;
using IRPanoView.Viewer.Network;

namespace IRPanoView.Viewer;

/// <summary>
/// Live view = raw U8 stitched pano over TCP 8769 (Jet on client, no encode).
/// Optional Record raw = per-camera radiometric tiles over TCP 8767.
/// </summary>
internal sealed class MainForm : Form
{
    private readonly PreviewStreamClient _preview = new();
    private readonly RawCameraStreamClient _raw = new();
    private readonly System.Windows.Forms.Timer _renderTimer = new() { Interval = 16 };

    private readonly PictureBox _picture = new()
    {
        Dock = DockStyle.Fill,
        BackColor = Color.Black,
        SizeMode = PictureBoxSizeMode.Zoom,
    };
    private readonly Label _stats = new()
    {
        Dock = DockStyle.Fill,
        AutoSize = false,
        TextAlign = ContentAlignment.TopLeft,
        Padding = new Padding(8, 4, 8, 4),
        AutoEllipsis = false,
    };
    private readonly TextBox _piHost = new() { Width = 140, Text = "10.254.254.1", Anchor = AnchorStyles.Left };
    private readonly NumericUpDown _previewPort = new() { Minimum = 1, Maximum = 65535, Value = 8769, Width = 70, Anchor = AnchorStyles.Left };
    private readonly NumericUpDown _rawPort = new() { Minimum = 1, Maximum = 65535, Value = 8767, Width = 70, Anchor = AnchorStyles.Left };
    private readonly Button _connect = new()
    {
        Text = "Connect",
        AutoSize = true,
        AutoSizeMode = AutoSizeMode.GrowAndShrink,
        MinimumSize = new Size(90, 28),
        Padding = new Padding(8, 0, 8, 0),
    };
    private readonly Button _record = new()
    {
        Text = "Record raw",
        AutoSize = true,
        AutoSizeMode = AutoSizeMode.GrowAndShrink,
        MinimumSize = new Size(100, 28),
        Padding = new Padding(8, 0, 8, 0),
        Enabled = false,
    };

    private Bitmap? _currentBitmap;
    private bool _connected;
    private DateTime _connectedAt;
    private bool _busy;

    public MainForm()
    {
        Text = "IRPanoView";
        Width = 1280;
        Height = 420;
        MinimumSize = new Size(720, 280);
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
        FormClosing += (_, e) =>
        {
            if (_busy)
            {
                e.Cancel = true;
                _stats.Text = "Finishing disconnect…";
                return;
            }

            Cleanup();
        };

        toolbar.Controls.Add(FieldLabel("Pi host"));
        toolbar.Controls.Add(_piHost);
        toolbar.Controls.Add(FieldLabel("Preview TCP"));
        toolbar.Controls.Add(_previewPort);
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
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 72f));
        root.Controls.Add(toolbar, 0, 0);
        root.Controls.Add(_picture, 0, 1);
        root.Controls.Add(_stats, 0, 2);

        Controls.Add(root);

        _stats.Text = "Connect for live U8 pano preview (TCP 8769, Jet on client). Record raw optional (8767).";
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
        if (_busy)
        {
            return;
        }

        try
        {
            _busy = true;
            _connect.Enabled = false;

            if (_connected)
            {
                Disconnect();
            }
            else
            {
                Connect();
            }
        }
        catch (Exception ex)
        {
            _stats.Text = "Connect error: " + ex.Message;
            try
            {
                Disconnect();
            }
            catch
            {
                // ignore
            }
        }
        finally
        {
            _busy = false;
            UpdateButtons();
        }
    }

    private void Connect()
    {
        var host = _piHost.Text.Trim();
        if (string.IsNullOrWhiteSpace(host))
        {
            _stats.Text = "Enter Pi host.";
            return;
        }

        _preview.Configure(host, (int)_previewPort.Value);
        _raw.Configure(host, (int)_rawPort.Value);
        _preview.Start();
        _connected = true;
        _connectedAt = DateTime.UtcNow;
        _renderTimer.Start();
        _stats.Text = $"Connecting preview {host}:{(int)_previewPort.Value}…";
    }

    private void Disconnect()
    {
        _renderTimer.Stop();
        try
        {
            if (_raw.IsRecording)
            {
                _raw.StopRecording();
            }
        }
        catch
        {
            // ignore
        }

        try
        {
            _raw.Stop();
        }
        catch
        {
            // ignore
        }

        try
        {
            _preview.Stop();
        }
        catch
        {
            // ignore
        }

        _connected = false;
        _stats.Text = "Disconnected.";
    }

    private void ToggleRecord()
    {
        if (!_connected || _busy)
        {
            return;
        }

        try
        {
            if (_raw.IsRecording)
            {
                _raw.StopRecording();
                _raw.Stop();
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

                if (!_raw.IsRunning)
                {
                    _raw.Start();
                }

                _raw.StartRecording(sessionDir);
            }
        }
        catch (Exception ex)
        {
            _stats.Text = "Record error: " + ex.Message;
        }

        UpdateButtons();
    }

    private void UpdateButtons()
    {
        _connect.Text = _connected ? "Disconnect" : "Connect";
        _connect.Enabled = !_busy;
        _record.Text = _raw.IsRecording ? "Stop record" : "Record raw";
        _piHost.Enabled = !_connected && !_busy;
        _previewPort.Enabled = !_connected && !_busy;
        _rawPort.Enabled = !_connected && !_busy;
        _record.Enabled = _connected && !_busy;
    }

    private void RenderFrame()
    {
        if (!_connected)
        {
            return;
        }

        try
        {
            if (_preview.TryTakeLatest(out var bitmap) && bitmap != null)
            {
                ReplaceBitmap(bitmap);
            }

            var rec = _raw.IsRecording ? $"  recording={_raw.RecordWritten}" : "";
            var rawErr = _raw.LastError != null ? $"  raw_err={_raw.LastError}" : "";
            var err = _preview.LastError != null ? $"  err={_preview.LastError}" : "";

            if (_currentBitmap == null)
            {
                var waited = (DateTime.UtcNow - _connectedAt).TotalSeconds;
                _stats.Text = $"Waiting for U8 preview ({waited:F0}s){err}{rec}{rawErr}";
                if (waited > 8)
                {
                    _stats.Text = $"Preview failed: {_preview.LastError ?? "no frames"} — disconnected.";
                    Disconnect();
                    UpdateButtons();
                }
            }
            else
            {
                var age = _preview.LastFrameAt is { } t
                    ? (DateTime.UtcNow - t).TotalSeconds
                    : 0;
                var stale = age > 1.5 ? $"  STALE {age:F0}s" : "";
                _stats.Text =
                    $"U8 {_preview.Width}x{_preview.Height}  {_preview.Fps:F1} fps  frames={_preview.FrameCount}  " +
                    $"range={_preview.MinC:F1}..{_preview.MaxC:F1}C{stale}{err}{rec}{rawErr}";
            }
        }
        catch (Exception ex)
        {
            _stats.Text = "Render error: " + ex.Message;
        }
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
        try
        {
            _renderTimer.Stop();
        }
        catch
        {
            // ignore
        }

        try
        {
            _raw.Dispose();
        }
        catch
        {
            // ignore
        }

        try
        {
            _preview.Dispose();
        }
        catch
        {
            // ignore
        }

        try
        {
            _picture.Image = null;
            _currentBitmap?.Dispose();
            _currentBitmap = null;
        }
        catch
        {
            // ignore
        }
    }
}
