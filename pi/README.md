# IRPanoView Pi host

Captures up to **4** Hik USB thermal cameras, edge-stitches to **1024×192**, and publishes:

- **RTSP H.264** Jet live view (port **8554**, default mount `/thermal`)
- **TCP IRPV v2** raw radiometry for timelapse (port **8767**, 0.1–1 Hz)
- **UDP latency metadata** (port **8768**)
- **WebSocket control** (port **8766**)

## Build (Pi 4, Raspberry Pi OS 64-bit)

```bash
sudo apt update
sudo apt install -y build-essential cmake pkg-config libusb-1.0-0-dev python3-pip avahi-daemon \
  gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  gstreamer1.0-rtsp libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libgstreamer-plugins-bad1.0-dev
cd pi
./deploy/install.sh
```

Demo mode without cameras:

```bash
./build/irpanoview-host --demo
```

## Network (current: shared WiFi AP)

Phone and Pi join the **same WiFi router/AP**. The Pi gets a DHCP address on your LAN.

- **RTSP live:** `rtsp://<pi>:8554/thermal`
- **Raw TCP timelapse:** port **8767**
- **Meta UDP:** port **8768** (broadcast)
- **WebSocket control:** port **8766**
- **mDNS host:** `irpanoview.local` (install Avahi via `install.sh`)

Set the Pi host in the viewer or Android app. Default is `irpanoview.local`; use a static LAN IP if mDNS is unavailable.

## Standalone mode (future)

Pi-as-hotspot configs are kept for later field use — see [deploy/hostapd.conf](deploy/hostapd.conf) and [deploy/dnsmasq.conf](deploy/dnsmasq.conf). Not required for shared-WiFi setup.

## Hub wiring

Use a **powered USB3 hub**. Keep camera hub ports fixed; pano left-to-right follows serial suffixes `07`, `86`, `02`, `97`.

## Protocol

See [proto/thermal_frame.md](proto/thermal_frame.md).
