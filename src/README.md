# IRPanoView

Android host for **2–4** TOPDON-style USB thermal cameras (UVC).

## Identity

Cameras are bound by **USB bus path** (`UsbDevice.getDeviceName()`, e.g. `/dev/bus/usb/002/005`), **not** serial — vendor init traffic does not carry a reliable per-unit serial.

Sorted bus order is paired with sorted Camera2 **external** camera ids by index.

## Build

1. Open this folder in **Android Studio** (Ladybug or newer).
2. Let Gradle sync; set SDK 35 if prompted.
3. Run on a device with **USB host** (OTG/hub), Android 10+ recommended for external Camera2.

```text
Build → Make Project
Run → app on hardware
```

## Runtime

1. Connect cameras on **fixed hub ports** (port = identity).
2. Launch app → allow **USB** and **Camera** if prompted.
3. Settings → **Refresh USB / request permission** if a tile shows “permission needed”.
4. Two cameras → 2×2 grid with live previews (when OEM exposes UVC as external Camera2).

**Demo mode** (Settings slider): layout only, no USB — used when `usb.host` devices are absent.

## Supported USB IDs

| VID | PID | Notes |
|-----|-----|--------|
| `0x2BDF` | `0x0102` | Current validated unit |
| `0x3474` | `0x4962` | TC001Max INF |

## Thermal decode (partial)

- [TopdonFrameDecoder.kt](app/src/main/java/com/topview/irpanoview/camera/TopdonFrameDecoder.kt): 256×384 YUYV → radiometric band → explicit [TemperatureModel](app/src/main/java/com/topview/irpanoview/camera/TemperatureModel.kt) conversion.
- TC001 runtime model default: `apk_runtime_reimpl_v1_tc001` (runtime-invoked APK-style conversion stage semantics: native-like value then `value/scale - 273.15`, `scale=16` branch).
- [ThermalLevelRegistry.kt](app/src/main/java/com/topview/irpanoview/camera/ThermalLevelRegistry.kt): global min/max for false-color scaling (not vendor calib).
- 1.0 display mapping trial: bounded dynamic shared Celsius window across cameras. Hard limits are `10C..50C`; values outside that are ignored for window calculation. If scene temps are narrower (for example `15C..32C`), display window shrinks to that narrower band.
- PC reference pipeline confirms absolute °C needs vendor NUC/env path.

## Not yet implemented

- Vendor calibration (NUC, FFC, emissivity USB init) and parity with TopView spot temperatures
- Range-bank selection tied to metadata sideband class (`0x80` / `0x11`)
- Vendor UVC XU init (`cfg80`, 34-byte commit) — stream may still work via kernel UVC
