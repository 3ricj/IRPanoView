# 12 — Display, visible band, and palette

Composite stream **format 0x67** includes a **98,304-byte visible band** (LUT grayscale YUYV) at **0x018800** in each **200,704-byte** UVC composite frame, plus a separate **radiometric** band at **0x0**. Display appearance is controlled through **image DeviceConfig commands** and optionally through **host-side colorization** of the radiometric grid.

---

## Visible preview band (known)

| Property | Value | Status |
|----------|-------|--------|
| Byte offset | **0x018800 – 0x0307FF** | **Known** |
| Composite rows | **196–387** | **Known** |
| Size | **98,304** B (**256×192**, **512 B/row**) | **Known** |
| Packed as | YUYV macropixel stream | **Known** |
| Content | **Grayscale / LUT** — **Y** luma varies; **U** and **V** ≈ **0x80** | **Known** |
| Independent of radiometric | Radiometric YUYV-like temp pairs at **0x0** are a separate band | **Known** |

Extract:

```
radiometric = frame[0x000000:0x018000]
visible     = frame[0x018800:0x030800]
```

Full composite layout: [07_Frame_Format_And_Decode.md](07_Frame_Format_And_Decode.md).

---

## Device-side display control paths (protocol capability)

The command set exposes **four related mechanisms**. All use DeviceConfig GET/SET unless noted.

### 1. Image enhancement — **0x7EA** GET / **0x7EB** SET

**Struct:** `USB_IMAGE_ENHANCEMENT`

| Field | Type | Role |
|-------|------|------|
| `byPaletteMode` | u8 | Palette / pseudo-color mode selector |
| `byLSEDetailEnabled` | u8 | Detail enhancement enable |
| `dwLSEDetailLevel` | u32 | Detail enhancement level |
| `byNoiseReduceMode` | u8 | Noise reduction mode |
| `dwGeneralLevel` | u32 | General enhancement level |
| `dwFrameNoiseReduceLevel` | u32 | Frame NR level |
| `dwInterFrameNoiseReduceLevel` | u32 | Inter-frame NR level |
| `byBirdWatchingMode` | u8 | Bird-watching ISP mode |
| `byHighLightMode` / `byHighLightLevel` | u8 | Highlight processing |
| `byHookEdgeMode` / `byHookEdgeLevel` | u8 | Edge hook processing |
| `byWideTemperatureMode` / `byWideTemperatureWork` | u8 | Wide-temperature ISP |
| `byIspAgcMode` | u8 | AGC mode |
| `byAISuperResolution` | u8 | AI super-resolution flag |
| `dwWideTemperatureUpThreshold` | u32 | Wide-temp upper threshold |
| `dwWideTemperatureDownThreshold` | u32 | Wide-temp lower threshold |

| Transport | Value |
|-----------|-------|
| Route | phase **(0x02, 0x05)**, wValue **0x0200** |
| GET wire | **79** B |
| SET wire | **176** B |

**Known:** `byPaletteMode = 2` is used as **white-hot** during standard init on TC002C Duo.

**Unknown:** Full enum for `byPaletteMode` (all valid values and labels). Effect of each field on visible-band byte content. Valid ranges for level fields on TC002C.

---

### 2. Image enhancement extended — **0x81F** GET / **0x820** SET

**Struct:** `USB_IMAGE_ENHANCEMENT_EX` (wraps nested `USB_IMAGE_ENHANCEMENT` plus extended ISP fields)

Additional fields include:

| Field | Role |
|-------|------|
| `byAGCMode` | AGC |
| `byIsothermEnabled` / `byIsothermalType` | Isotherm display |
| `dwIsothermalUpperThreshold` / `dwIsothermalLowerThreshold` | Isotherm bounds |
| `byColorAlarmType` / `dwColorAlarmUpperLimit` / `dwColorAlarmLowerLimit` | Color alarm |
| `byBurnPreventionEnabled` / related | Burn prevention |
| `byGaussianFilterEnabled` / bilateral filter fields | Filtering |
| `dwRelativeHumidity` / `dwAtmosphericTemperature` | Environment for ISP |
| `byAutoShutEnabled` | Auto shutter tie-in |

**Known:** Struct exists in protocol; command IDs assigned.

**Unknown:** Host/wire sizes on TC002C. Which fields TC002C firmware honors. Whether TC002C init path uses **0x820** or only **0x7EB**.

---

### 3. Single palette LUT — **0x839** GET / **0x843** SET

**Struct:** `USB_IMAGE_PALETTE_DATA`

| Field | Type | Limit |
|-------|------|-------|
| `byPaletteMode` | u8 | Mode index |
| `dwDataLen` | u32 | Payload length |
| `pData` | u8[] | Up to **10,240** bytes LUT data |
| `szPaletteName` | string | Name label |

**Known:** SET accepts uploaded palette blob up to 10 KB. GET returns current palette data and mode.

**Unknown:** LUT format (entries, bit depth, channel mapping). Whether SET changes subsequent visible-band bytes. Relationship between `byPaletteMode` here vs in **0x7EB**. Whether TC002C ships factory LUTs only via GET or expects host upload.

---

### 4. Multi-palette bank — **0x847** GET (+ capabilities **0x858**)

**Struct:** `USB_IMAGE_MULTI_PALETTE_DATA`

| Field | Type | Limit |
|-------|------|-------|
| `byPaletteNumber` | u8 | Count of palettes returned |
| `struDataList[]` | array | Up to **20** entries |

Each entry (`USB_IMAGE_SINGLE_PALETTE_DATA`):

| Field | Type | Limit |
|-------|------|-------|
| `byPaletteID` | u8 | Palette identifier |
| `byCustomizeEnabled` | u8 | Custom flag |
| `dwPaletteDataLength` | u32 | Data length |
| `pPaletteData` | u8[] | Up to **10,240** B per palette |
| `szPaletteName` | string | Name |

**Capabilities struct:** `IMAGE_MULTI_PALETTE_DATA_CAPABILITIES` — GET **0x858**

**Known:** GET **0x847** reads palette library. Max 20 palettes × 10 KB data. Capability query command exists.

**Unknown:** SET command ID for writing multi-palette bank (not exposed in standard interface constants). **0x858** field layout and supported features on TC002C. Factory default palette IDs and names on TC002C Duo.

---

### 5. Stream YUV resolution — **0x853** GET / **0x855** capabilities

**Struct:** `THERMAL_STREAM_YUV_RESOLUTION`

**Known:** Command exists in catalog for querying YUV stream resolution parameters.

**Unknown:** Struct fields, wire sizes, relationship to 256×192 bands in the composite. Whether SET exists and affects format **0x67** layout.

---

### 6. Stream encode param — **0x851** (`THERMOMETRY_STREAM_ENCODE_PARAM`)

**Known:** Command ID in catalog — may govern how the visible band is encoded in the composite frame.

**Unknown:** All fields. TC002C relevance.

---

## Image enhancement capabilities — GET **0x81B**

**Struct:** `IMAGE_ENHANCEMENT_CAPABILITIES`

**Known:** Command exists.

**Unknown:** Bitmask/field definitions. Which palette modes, NR levels, and ISP features TC002C reports as supported.

---

## Relationship: visible band vs radiometric band

| Question | Status |
|----------|--------|
| Are radiometric and visible bands from the same sensor frame? | **Assumed yes** — same composite frame timestamp |
| Does `byPaletteMode` on **0x7EB** change visible-band bytes? | **Unknown** — not verified on wire |
| Does SET **0x843** change visible-band bytes? | **Unknown** |
| Can visible band be used without radiometric for temperature? | **No** — radiometry requires band @ **0x0** |
| Can radiometric band be used without visible for temperature-only host? | **Yes** |
| Is visible band pre-colored or grayscale on wire? | **Grayscale/LUT** — Y varies, chroma neutral (**≈ 0x80**) |

---

## Host-side colorization (alternative to device palette)

Independently of device commands, a host may build display RGB from the **radiometric grid** (post **+0x37C0** bias, `/64 − 273.15`) using an arbitrary LUT. This is **not** a device protocol feature; it does not require **0x839/0x843/0x847**.

**Known:** Grid decode formula (see [07_Frame_Format_And_Decode.md](07_Frame_Format_And_Decode.md)).

**Unknown:** Whether device visible band and host grid-colorization are calibrated to match when the same palette name is selected.

---

## Summary table

| Mechanism | GET | SET | Known on TC002C | Open |
|-----------|-----|-----|-----------------|------|
| Enhancement + palette mode | 0x7EA | 0x7EB | White-hot = mode **2** at init | Full mode enum; YUV effect |
| Enhancement extended | 0x81F | 0x820 | Struct defined | Usage on TC002C |
| Single palette LUT | 0x839 | 0x843 | Max 10 KB upload | LUT format; wire effect |
| Multi-palette bank | 0x847 | **?** | Max 20 × 10 KB read | SET id; **0x858** layout |
| YUV resolution | 0x853 | **?** | Command exists | Struct; SET |
| Enhancement caps | 0x81B | — | Command exists | Field defs |
