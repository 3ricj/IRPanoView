# IRPanoView Windows thermal viewer

C# WinForms client for the dual-stream Pi host:

- **Live:** LibVLC RTSP H.264 Jet pano (low latency)
- **Timelapse:** TCP IRPV v2 raw radiometry recorder (0.1–1 Hz from Pi)
- **Latency:** UDP metadata panel + CSV export

## Requirements

- [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0)
- Windows (WinForms)
- PC on the **same LAN** as the Pi

## Snapshot (CLI inspect one frame)

Legacy UDP snapshot tools may still work if an old host is running. For the dual-stream host, use **Record raw** in the GUI or connect a TCP client to port **8767**.

```powershell
cd viewer
dotnet run --project IRPanoView.Viewer -- --snapshot --out snapshots --timeout 30
```

## Run (GUI)

```powershell
cd viewer
dotnet run --project IRPanoView.Viewer
```

Or open `IRPanoView.Viewer.sln` in Visual Studio and run.

## Usage

1. Start the Pi host (`irpanoview-host`, demo or live cameras).
2. Click **Connect** (default RTSP `rtsp://192.168.8.115:8554/thermal`).
3. Optional: **Record raw** to save IRPV v2 frames from TCP **8767**.
4. **Export latency CSV** during a live session for tuning.

## Protocol

See [`../pi/proto/thermal_frame.md`](../pi/proto/thermal_frame.md).
