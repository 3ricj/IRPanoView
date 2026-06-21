# IRPanoView Android viewer

Network client for the Pi-hosted 1024×192 thermal pano strip.

## Build

1. Open this folder in **Android Studio** (Ladybug or newer).
2. Let Gradle sync; set SDK 35 if prompted.
3. Run on a phone/tablet (Android 9+).

## Runtime (shared WiFi)

1. Pi and phone on the **same WiFi AP** (router; not Pi hotspot for now).
2. Pi running `irpanoview-host` + control service (see [pi/README.md](../pi/README.md)).
3. Android → Settings → **Pi host** — default `irpanoview.local`, or enter the Pi's LAN IP.
4. Tap **Connect Pi**.

Ports: UDP **8765** (thermal), WebSocket **8766** (control).

## Demo mode

Settings → **Demo mode** shows a synthetic pano strip without Pi hardware.

## Standalone mode (future)

Pi-as-hotspot (`IRPanoView` SSID) configs live under `pi/deploy/` for later migration.

## Decode

- [PanoFrameDecoder.kt](app/src/main/java/com/vilos/irpanoview/camera/PanoFrameDecoder.kt) — flat 1024×192 u16 grid → false color
- [HikTherm.kt](app/src/main/java/com/vilos/irpanoview/camera/hik/HikTherm.kt) — radiometric °C conversion
