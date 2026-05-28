# 13 — Extended command catalog

Complete DeviceConfig command inventory for the Hik USB thermal protocol. **207** command kinds appear in firmware command templates. GET command decimal IDs follow the pattern: even kind IDs map to `USB_GET_*`; SET is typically kind ID + 1 (exceptions exist — verify per command).

Convention: **Hex** = command ID on wire via `USB_GetDeviceConfig` / `USB_SetDeviceConfig`. **TC002C** column marks commands used in the standard TC002C Duo streaming path.

---

## Session, system, and gate

| Hex | GET | SET | Struct tag | TC002C | Notes |
|-----|-----|-----|------------|--------|-------|
| 0x7DB | ✓ | ✓ | `SYSTEM_DEVICE_INFO` | **P0** | **488 B** at bind — see [15](15_Device_Info_And_Identity.md) |
| 0x7DE | ✓ | — | `SYSTEM_HARDWARE_SERVER` | **P0** | Readiness; byte[2] status 2/3 = stream-ready |
| 0x7E0 | ✓ | ✓ | `SYSTEM_LOCALTIME` | — | |
| 0x7E8 | ✓ | — | `SYSTEM_DIAGNOSED_DATA` | — | Diagnostics |
| 0x824 | — | — | `SYSTEM_INIT` | — | |
| 0x827 | ✓ | — | `SYSTEM_DEVICE_INFO_CAPABILITIES` | — | u8 per-field flags — [15](15_Device_Info_And_Identity.md) |
| 0x836 | ✓ | ✓ | `SYSTEM_DEVICE_DESCRIPTION_INFO` | **P2** | **417 B** GET; optional (not bind) — [15](15_Device_Info_And_Identity.md) |
| 0x837 | ✓ | ✓ | `SYSTEM_SERIAL_DATA_TRANSMISSION` | **P1** | **Command tunnel** (not SN storage) — [09](09_Shutter_And_Maintenance.md) |
| 0x83D | ✓ | — | `SYSTEM_DEVICE_DESCRIPTION_INFO_CAPABILITIES` | — | u8 per-field flags — [15](15_Device_Info_And_Identity.md) |
| 0xFD3 | ✓ | — | `SYSTEM_DEVICE_CAPABILITIES` | — | |

---

## Video stream

| Hex | GET | SET | Struct tag | TC002C | Wire / known |
|-----|-----|-----|------------|--------|--------------|
| 0xBBB | ✓ | ✓ | `VIDEO_PARAM` | **P0** | fmt **0x67**, w=8, h=0x3122, fps=25 |
| 0x7F6 | ✓ | ✓ | `THERMAL_STREAM_PARAM` | — | ~20 B; `byVideoCodingType` |
| 0x83E | ✓ | — | `CTRL_THERMAL_STREAM_PARAM` | — | |
| 0x853 | ✓ | — | `THERMAL_STREAM_YUV_RESOLUTION` | — | **Open** |
| 0x851 | ✓ | — | `THERMOMETRY_STREAM_ENCODE_PARAM` | — | **Open** |
| 0x814 | ✓ | — | `VIDEO_CODE` | — | |
| 0x816 | ✓ | — | `VIDEO_FUSION` | — | |

Stream delivery itself uses **bulk IN** (not a DeviceConfig GET). Typical endpoint **0x81**, **201,248** B per frame.

---

## Image — adjust, brightness, contrast

| Hex | GET | SET | Struct tag | TC002C | Wire SET |
|-----|-----|-----|------------|--------|----------|
| 0x7EC | ✓ | ✓ | `IMAGE_VIDEO_ADJUST` | **P0** | **41** B |
| 0x7E2 | ✓ | ✓ | `IMAGE_BRIGHTNESS` | — | **Open** |
| 0x7E4 | ✓ | ✓ | `IMAGE_CONTRAST` | **P1** | **Open** |

---

## Image — enhancement and palette

| Hex | GET | SET | Struct tag | TC002C | Wire SET |
|-----|-----|-----|------------|--------|----------|
| 0x7EA | ✓ | ✓ | `IMAGE_ENHANCEMENT` | **P0** | **176** B |
| 0x820 | ✓ | ✓ | `IMAGE_ENHANCEMENT_EX` | — | **Open** |
| 0x839 | ✓ | ✓ | `IMAGE_PALETTE_DATA` | **Cap** | LUT ≤ 10,240 B |
| 0x847 | ✓ | **?** | `IMAGE_MULTI_PALETTE_DATA` | **Cap** | Up to 20 palettes |
| 0x83B | ✓ | — | `IMAGE_OUTPUT_CFG` | — | **Open** |
| 0xC3E | ✓ | ✓ | `IMAGE_DYNAMIC_RANGE_PARAM` | — | |
| 0xFD5 | ✓ | ✓ | `IMAGE_WDR` | — | |

See [12_Display_And_Palette.md](12_Display_And_Palette.md) for palette known/unknown detail.

---

## Image — capabilities (GET-only cluster)

| Hex | Struct tag | Notes |
|-----|------------|-------|
| 0x81A | `IMAGE_CAPABILITIES` | |
| 0x81B | `IMAGE_ENHANCEMENT_CAPABILITIES` | Palette/ISP feature flags — **fields open** |
| 0x81C | `THERMAL_CAPABILITIES` | |
| 0x82A | `IMAGE_BRIGHTNESS_CAPABILITIES` | |
| 0x82B | `IMAGE_CONTRAST_CAPABILITIES` | |
| 0x82C | `IMAGE_VIDEO_ADJUST_CAPABILITIES` | |
| 0x822 | `THERMAL_STREAM_PARAM_CAPABILITIES` | |
| 0x823 | `VIDEO_CODE_CAPABILITIES` | |
| 0x855 | `THERMAL_STREAM_YUV_RESOLUTION_CAPABILITIES` | |
| 0x858 | `IMAGE_MULTI_PALETTE_DATA_CAPABILITIES` | **Open** |
| 0x848 | `IMAGE_FOCUS_CAPABILITIES` | |

**Unknown on TC002C:** All capability struct field layouts and which bits are set for this hardware.

---

## Thermometry — basic, mode, regions

| Hex | GET | SET | Struct tag | TC002C | Wire SET |
|-----|-----|-----|------------|--------|----------|
| 0x7EE | ✓ | ✓ | `THERMOMETRY_BASIC_PARAM` | **P0** | **80** B (host struct 260 B) |
| 0x7F0 | ✓ | ✓ | `THERMOMETRY_MODE` | — | ~70 B; unused on TC002C gain path |
| 0x7F2 | ✓ | ✓ | `THERMOMETRY_REGIONS` | — | ~556 B |
| 0x849 | ✓ | ✓ | `THERMOMETRY_BASIC_PARAM` | — | Duplicate kind tag in catalog — relationship **open** |

Gain/range on TC002C uses **0x7EE/0x7EF** field `byTemperatureRange`, not **0x7F1**.

---

## Thermometry — correction, calibration, expert

| Hex | GET | SET | Struct tag | TC002C |
|-----|-----|-----|------------|--------|
| 0x7F8 | ✓ | ✓ | `TEMPERATURE_CORRECT` | — |
| 0x80F | ✓ | ✓ | `ENVIROTEMPERATURE_CORRECT` | — |
| 0x80D | ✓ | ✓ | `THERMOMETRY_RISE_SETTINGS` | — |
| 0x806 | ✓ | ✓ | `THERMOMETRY_CALIBRATION_FILE` | — |
| 0x808 | ✓ | ✓ | `THERMOMETRY_EXPERT_REGIONS` | — |
| 0x80A | ✓ | ✓ | `THERMOMETRY_EXPERT_CORRECTION_PARAM` | — |
| 0x7FA | ✓ | ✓ | `BLACK_BODY` | — |
| 0x7FC | ✓ | ✓ | `BODYTEMP_COMPENSATION` | — |
| 0x83A | ✓ | — | `THERMOMETRY_OFFLINE_DATA` | — |
| 0x84B | ✓ | — | `THERMOMETRY_SECOND_CALIBRATION` | — |
| 0x84D | ✓ | — | `THERMOMETRY_CALIBRATION_TEMPLATE_PARAM` | — |
| 0x84F | ✓ | — | `THERMOMETRY_CALIBRATION_FILE_CTRL` | — |

Matching `*_CAPABILITIES` commands exist at **0x81D–0x835** (see capability cluster in catalog source).

---

## Measurement — ROI search

| Hex | GET | SET | Struct tag | TC002C |
|-----|-----|-----|------------|--------|
| 0x7FF | ✓ | — | `ROI_MAX_TEMPERATURE_SEARCH` | **Cap** — firmware ROI query ~516 B result |

**Unknown:** Whether SET **0x800** exists for ROI config (decimal ID collision with `P2P_PARAM` in interface — verify on device).

---

## Shutter and manual correct

| Hex | API style | Struct | TC002C | Payload |
|-----|-----------|--------|--------|---------|
| 0x7E9 | **Control** (not DeviceConfig UVC pack) | `USB_COMMON_COND` | **P1** | **12** B, `byChannelID=1` |
| 0xC2D | DeviceConfig GET | `SHUTTER_PARAM` | — | ~136 B |
| 0xC2E | DeviceConfig SET | `SHUTTER_PARAM` | — | |

Inner serial opcodes via **0x838** SET: **0x2001** (auto-shutter), **0xF026** (calibration).

---

## Thermometry algorithm / version

| Hex | GET | Struct tag |
|-----|-----|------------|
| 0x7F4 | ✓ | `THERMAL_ALG_VERSION` |

---

## Command state and control

| Hex | Role |
|-----|------|
| 0x0600 (wValue) | UVC status poll — 1 byte completion |
| 0xF9F | `COMMAND_STATE` (template kind 3999) |

---

## Non-thermal commands in same protocol (abbreviated)

The full firmware catalog includes commands for other product lines. Not expected on TC002C Duo USB thermal path:

| Range | Examples |
|-------|----------|
| 0x3E9–0x405 | Card reader, certificate, fingerprint |
| 0xBC6–0xBED | Generic UVC video properties (brightness, pan, tilt, zoom) |
| 0xC27–0xC6C | White balance, gamut, VCA, audio |
| 0xC80–0xCAD | Factory test commands |
| 0x1005–0x1017 | Video analytics (face detect, etc.) |
| 0x1388–0x1396 | Spectral sensor, firmware update |

Full 207-entry listing is in firmware command template registry (kind ID 1001–5014).

---

## Control vs DeviceConfig

| Style | Used for | Examples |
|-------|----------|----------|
| `USB_GetDeviceConfig` / `USB_SetDeviceConfig` | UVC-routed structs | 0x7EF, 0x7EB, 0xBBC, 0x843 |
| `USB_Control` | Immediate actions, 12 B cond | 0x7E9 manual shutter |

Both terminate in USB control transfers on extension unit **wIndex** (typically **0x0A00**).
