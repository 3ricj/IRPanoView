# 04 — Thermometry parameters (0x7EE / 0x7EF)

Thermometry environment and range settings use struct **`USB_THERMOMETRY_BASIC_PARAM`**. The host holds a **260-byte (0x104)** layout; the USB wire payload for GET/SET is a compact **80-byte (0x50)** map.

---

## Command IDs

| Operation | ID |
|-----------|-----|
| GET | **0x7EE** |
| SET | **0x7EF** |

Transport: UVC route phase **(0x03, 0x01)**, data **wValue = 0x0300**, wire length **80**.

Pattern:

```
GET 0x7EE  →  edit fields  →  SET 0x7EF  →  poll command state
```

---

## Host struct layout (260 bytes)

| Offset | Field | Size | Type |
|-------:|-------|-----:|------|
| 0x00 | `dwSize` | 4 | u32 — set to 260 |
| 0x04 | `byTemperatureRangeAutoChangedEnabled` | 1 | u8 |
| 0x05 | `byEnabled` | 1 | u8 |
| 0x06 | `byDisplayMaxTemperatureEnabled` | 1 | u8 |
| 0x07 | `byDisplayMinTemperatureEnabled` | 1 | u8 |
| 0x08 | `byDisplayAverageTemperatureEnabled` | 1 | u8 |
| 0x09 | `byTemperatureUnit` | 1 | u8 |
| **0x0A** | **`byTemperatureRange`** | 1 | u8 — **gain/range selector** |
| 0x0B | `byCalibrationCoefficientEnabled` | 1 | u8 |
| 0x0C | `dwCalibrationCoefficient` | 4 | u32 |
| 0x10 | `dwExternalOpticsWindowCorrection` | 4 | u32 |
| **0x14** | **`dwEmissivity`** | 4 | u32 — **×100** |
| 0x18 | `byDistanceUnit` | 1 | u8 |
| 0x19 | `byShowAlarmColorEnabled` | 1 | u8 |
| 0x1A | `byAlarmType` | 1 | u8 |
| 0x1B | `byAlarmRult` | 1 | u8 |
| **0x1C** | **`dwDistance`** | 4 | u32 — **metres ×100** |
| 0x20 | `byReflectiveEnable` | 1 | u8 |
| 0x21 | `byAutoDrift` | 1 | u8 |
| 0x22 | `bySSECompensation` | 1 | u8 |
| 0x23 | `byThermalRawData` | 1 | u8 |
| **0x24** | **`dwReflectiveTemperature`** | 4 | u32 |
| 0x28 | `byThermomrtryInfoDisplayPosition` | 1 | u8 |
| **0x29** | **`byThermometryStreamOverlay`** | 1 | u8 — **1 = overlay off** |
| 0x2A | `byDisplayCenTempEnabled` | 1 | u8 |
| 0x2B | `byBackcolorEnabled` | 1 | u8 |
| 0x2C | `dwAlert` | 4 | u32 |
| 0x30 | `dwAlarm` | 4 | u32 |
| 0x34 | `dwExternalOpticsTransmit` | 4 | u32 |
| 0x38 | `dwTemperatureRangeUpperLimit` | 4 | u32 |
| 0x3C | `dwTemperatureRangeLowerLimit` | 4 | u32 |
| 0x40 | `dwTemperatureRangeExcursionUpperLimit` | 4 | u32 |
| 0x44 | `dwTemperatureRangeExcursionLowerLimit` | 4 | u32 |
| 0x48 | `dwAtmosphericHumidity` | 4 | u32 |
| 0x4C | `byFloatTransMode` | 1 | u8 |
| 0x4D | `byShiftLength` | 1 | u8 |
| **0x4E** | **`byEnviromentTemperatureEnable`** | 1 | u8 — **2 = enabled** |
| 0x4F | `byRes1` | 1 | u8 |
| **0x50** | **`dwEnviromentTemperature`** | 4 | u32 — **(°C×100)+10000** |
| 0x54 | `byRes[176]` | 176 | padding |

---

## Field encodings

### Emissivity — `dwEmissivity` @ 0x14

```
wire_value = round(UI_emissivity × 100)
```

| UI | Wire |
|----|------|
| 0.95 | 95 |
| 0.90 | 90 |
| 0.85 | 85 |

### Distance — `dwDistance` @ 0x1C

```
wire_value = round(distance_metres × 100)
```

| UI | Wire |
|----|------|
| 1.00 m | 100 |
| 0.50 m | 50 |

### Ambient temperature — `dwEnviromentTemperature` @ 0x50

Requires `byEnviromentTemperatureEnable = 2`.

```
wire_value = round(UI_celsius × 100) + 10000
```

| UI °C | Wire |
|-------|------|
| 30 | 13000 |
| 25 | 12500 |
| 20 | 12000 |

Decode: `UI_celsius = (wire_value − 10000) / 100`

### Temperature range / gain — `byTemperatureRange` @ 0x0A

Used with `byTemperatureRangeAutoChangedEnabled` @ 0x04:

| UI mode | Auto-changed | Range byte | Label |
|---------|:------------:|:----------:|-------|
| Auto | **1** | 2 | Automatic range |
| Normal | **0** | **2** | Normal (-20~150 °C class) |
| High | **0** | **3** | High temperature |

Gain/range on TC002C Duo is controlled only through **0x7EF**. Commands **0x7F0/0x7F1** (thermometry mode struct) are not used on this product path.

### Stream overlay — `byThermometryStreamOverlay` @ 0x29

| Value | Meaning |
|-------|---------|
| **1** | Do not burn thermometry graphics into encoded stream (init default) |
| other | Device-dependent overlay modes |

---

## 80-byte wire map (on USB)

The host repacks the 260 B struct into an 80 B wire map. Patch these offsets when building SET payloads directly:

| Wire offset | Field |
|------------:|-------|
| **0x02** | Thermometry stream overlay |
| **0x06** | `byTemperatureRange` |
| **0x10** | `dwEmissivity` (u32 LE) |
| **0x16** | `dwDistance` (u32 LE) |
| ~0x0C, ~0x1C | Environment-related dwords (preserve from GET baseline) |

Example baseline head (80 bytes total):

```
01 01 01 00 00 01 02 00 c8 00 00 00 b0 04 00 00
5a 00 00 00 02 7d 00 00 00 00 b0 04 00 00 02 01
…
```

Sample decode: range=2 (Normal), emissivity=90, distance=100.

Therm SET control transfer:

```
OUT  bm=0x21  bRequest=0x01  wValue=0x0300  wIndex=0x0A00  len=80
```

---

## Configuration operations

Two logical updates share **0x7EF** but touch disjoint fields:

### Environment config

GET **0x7EE**, modify only:

- `dwEmissivity`
- `dwDistance`
- `byEnviromentTemperatureEnable` (set **2** when ambient provided)
- `dwEnviromentTemperature`

SET **0x7EF**, poll.

### Temperature range / gain

GET **0x7EE**, modify only:

- `byTemperatureRangeAutoChangedEnabled`
- `byTemperatureRange`

SET **0x7EF**, poll.

Recommended order when applying both: **range first**, then **environment**.

---

## initConfig therm behavior

On first stream setup, therm SET is skipped if `byThermometryStreamOverlay` is already **1**. Otherwise only that byte is forced to **1**; all other fields are preserved from the GET snapshot.

---

## Reflective temperature

`dwReflectiveTemperature` @ 0x24 is in the struct but not exposed in the TC002C standard parameters UI. Leave at device default unless extended by the host application.

---

## Do not confuse with stream decode scale

Ambient encoding `(°C×100)+10000` applies only to **0x7EF** configuration. Stream pixel values use **`stored_u16 / 64 − 273.15`** (see [07_Frame_Format_And_Decode.md](07_Frame_Format_And_Decode.md)).

---

## Known vs unknown (thermometry)

| Topic | Known | Unknown |
|-------|-------|---------|
| `byTemperatureRange` | **2** Normal, **3** High; auto uses **2** with auto-changed **1** | Additional range codes on other SKUs |
| `byTemperatureUnit` | Field @ 0x09 | Valid values (°C vs °F) |
| `byAlarmType` / `byAlarmRult` | Fields exist | Alarm semantics on TC002C |
| `dwReflectiveTemperature` | Field @ 0x24 | Encoding when enabled |
| `dwAtmosphericHumidity` | Field @ 0x48 | Encoding and effect on grid |
| 80 B wire map | Partial offsets documented above | Full 80 B ↔ 260 B field map |
| **0x849** | Same struct tag in catalog | Relationship to **0x7EE** |

Open items registry: [14_Protocol_Known_And_Unknown.md](14_Protocol_Known_And_Unknown.md).
