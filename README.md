# IRPanoView

Network viewer for a **4-camera thermal pano strip** streamed from a Raspberry Pi 4.

## Architecture

- **Pi 4** — USB host for 4 Hik thermal cameras, edge-stitch to 1024×192, UDP stream + WebSocket control
- **Android app** — on the **same WiFi AP** as the Pi, receives raw radiometric frames, applies palette/range locally

Standalone Pi hotspot mode is planned for later field use.

See [pi/README.md](pi/README.md) for the host service and [src/README.md](src/README.md) for the Android viewer.

## Protocol reference

[MasterThermoDocs/](MasterThermoDocs/) documents the Hik USB protocol used by the Pi host.

## Repository layout

- `pi/` — Pi host (C++ capture, UDP stream, WebSocket control bridge)
- `src/` — Android network viewer
- `MasterThermoDocs/` — USB protocol reference (used by Pi port)
