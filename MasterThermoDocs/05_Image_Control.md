# 05 — Image control

Image parameters use the same UVC DeviceConfig transport as thermometry. All follow GET → modify → SET with command-state polling.

---

## Image video adjust (0x7EC / 0x7ED)

**Struct:** `USB_IMAGE_VIDEO_ADJUST`

| Property | Detail |
|----------|--------|
| Route | phase **(0x02, 0x06)**, wValue **0x0200** |
| GET wire | **31** B |
| SET wire | **41** B |

### initConfig mutations

| Goal | Action |
|------|--------|
| Landscape **256×192** | Set orientation for horizontal display |
| Corridor off | Disable corridor / rotation modes |
| Mirror | Preserve device mirror unless user toggles |

### Mirror (runtime)

GET **0x7EC**, flip mirror bit in wire map, SET **0x7ED**, poll.

---

## Image enhancement (0x7EA / 0x7EB)

**Struct:** `USB_IMAGE_ENHANCEMENT`

| Property | Detail |
|----------|--------|
| Route | phase **(0x02, 0x05)**, wValue **0x0200** |
| GET wire | **79** B |
| SET wire | **176** B |

| Field | Type | Known | Unknown |
|-------|------|-------|---------|
| `byPaletteMode` | u8 | **2** = white-hot at init | Full enum |
| `byLSEDetailEnabled` | u8 | Field exists | Valid values |
| `dwLSEDetailLevel` | u32 | Field exists | Range on TC002C |
| `byNoiseReduceMode` | u8 | Field exists | Mode enum |
| `dwGeneralLevel` | u32 | Field exists | Range |
| `dwFrameNoiseReduceLevel` | u32 | Field exists | Range |
| `dwInterFrameNoiseReduceLevel` | u32 | Field exists | Range |
| `byBirdWatchingMode` | u8 | Field exists | TC002C support |
| `byHighLightMode` / `byHighLightLevel` | u8 | Fields exist | Semantics |
| `byHookEdgeMode` / `byHookEdgeLevel` | u8 | Fields exist | Semantics |
| `byWideTemperatureMode` / `byWideTemperatureWork` | u8 | Fields exist | Semantics |
| `byIspAgcMode` | u8 | Field exists | Mode enum |
| `byAISuperResolution` | u8 | Field exists | TC002C support |
| `dwWideTemperatureUpThreshold` | u32 | Field exists | Units |
| `dwWideTemperatureDownThreshold` | u32 | Field exists | Units |

### initConfig mutations (known)

| Goal | Wire hint |
|------|-----------|
| White-hot palette | **`byPaletteMode` = 2** at **wire byte 0x05** (ConvertData compact map) |
| Detail enhancement | Enable `byLSEDetailEnabled` |

### Detail enhance level (runtime)

Adjust DDE/detail level via **0x7EB** — valid `dwLSEDetailLevel` range **unknown**.

---

## Image enhancement extended (0x81F / 0x820)

**Struct:** `USB_IMAGE_ENHANCEMENT_EX` — nested `USB_IMAGE_ENHANCEMENT` plus AGC, isotherm, color alarm, burn prevention, filter, and environment fields.

**Known:** Command IDs and struct exist in protocol.

**Unknown:** Wire sizes on TC002C; whether init uses **0x820** instead of or in addition to **0x7EB**. See [12_Display_And_Palette.md](12_Display_And_Palette.md).

---

## Palette LUT commands

| GET | SET | Struct | Known | Unknown |
|-----|-----|--------|-------|---------|
| **0x839** | **0x843** | `USB_IMAGE_PALETTE_DATA` | LUT up to **10,240** B; `byPaletteMode` + name | LUT entry format; wire effect on visible band |
| **0x847** | **?** | `USB_IMAGE_MULTI_PALETTE_DATA` | Up to **20** palettes via GET | SET command ID; factory defaults on TC002C |
| **0x858** | — | `IMAGE_MULTI_PALETTE_DATA_CAPABILITIES` | Command exists | Field layout |

---

## Image contrast (0x7E4 / 0x7E5)

**Struct:** `USB_IMAGE_CONTRAST`

| Property | Detail |
|----------|--------|
| wValue channel | **0x0200** |

Typical UI value: contrast **50** on a 0–100 scale. GET **0x7E4**, SET **0x7E5**.

---

## Pseudo-color and display

Two independent paths:

| Path | Mechanism | Known |
|------|-----------|-------|
| Device | **0x7EB** `byPaletteMode`, **0x843** LUT upload, **0x847** multi-palette GET | Mode **2** = white-hot; 10 KB LUT cap |
| Host | LUT on decoded grid (post **+0x37C0**, `/64 − 273.15`) | Decode formula fixed |

**Unknown:** Whether device visible band and host grid-colorization match for the same palette name. See [12_Display_And_Palette.md](12_Display_And_Palette.md).

---

## Digital zoom

Center-down zoom stepping operates on preview path 0. Not a thermometry DeviceConfig command.

---

## Burn protection

Burn-protection flags live in **0x7EB** enhancement fields. Shutter/NUC actions are separate commands (see [09_Shutter_And_Maintenance.md](09_Shutter_And_Maintenance.md)).

---

## Command summary

| Feature | GET | SET | Wire SET len |
|---------|----:|----:|-------------:|
| Video adjust / mirror | 0x7EC | 0x7ED | 41 |
| Enhancement / DDE | 0x7EA | 0x7EB | 176 |
| Enhancement extended | 0x81F | 0x820 | **open** |
| Single palette LUT | 0x839 | 0x843 | ≤ 10,240 B |
| Multi-palette bank | 0x847 | **?** | GET only known |
| Contrast | 0x7E4 | 0x7E5 | compact |

---

## Pseudocode

```
def set_enhancement(white_hot=True):
    raw = uvc_get(route=IMAGE_ENHANCE)   # GET 0x7EA, 79 B
    buf = pad_to(raw, 176)
    if white_hot:
        buf[5] = 0x02   # byPaletteMode @ wire byte 5 (not byte 1)
    uvc_set(wvalue=0x0200, payload=buf)  # SET 0x7EB
    poll_command_state()
```
