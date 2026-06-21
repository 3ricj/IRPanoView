# Pi deployment notes

## Current setup: shared WiFi AP

Phone and Pi on the **same router/AP**. No hostapd required.

1. Run `./install.sh` on the Pi.
2. Ensure Pi and phone can reach each other (same VLAN; no client isolation on the AP if possible).
3. Viewer or Android app → Pi host (`irpanoview.local` or Pi LAN IP).

Services:

- `irpanoview-host.service` — USB capture, compositor, RTSP **8554**, raw TCP **8767**, meta UDP **8768**
- `irpanoview-control.service` — WebSocket 8766

mDNS: `avahi-irpanoview.service` advertises **`irpanoview.local`** with `rtsp=8554`, `raw=8767`, `meta=8768`.

## Standalone / field mode (future)

When migrating off shared WiFi, enable Pi hotspot using [hostapd.conf](hostapd.conf) and [dnsmasq.conf](dnsmasq.conf) (Pi at `192.168.4.1`, SSID `IRPanoView`).

## Recovery

If a camera drops, restart after hub power events:

```bash
sudo systemctl restart irpanoview-host irpanoview-control
```

`irpanoview-host.service` is installed but **not** auto-enabled by default. Start manually: `systemctl start irpanoview-host` (after `sudo systemctl enable` if you want boot autostart).
