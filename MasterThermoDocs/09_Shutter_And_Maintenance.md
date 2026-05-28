# 09 — Shutter and maintenance

Shutter (NUC / flat-field correction) and auto-shutter policy use **two command planes**.

---

## Manual shutter (immediate NUC)

One-shot correction on user request.

| Property | Value |
|----------|-------|
| Command | **0x7E9** |
| Style | Control command (not DeviceConfig UVC pack) |
| Struct | `USB_COMMON_COND` — **12 bytes** |

### `USB_COMMON_COND` layout

| Offset | Field | Shutter value |
|-------:|-------|---------------|
| 0x00 | `dwSize` | 12 |
| 0x04 | `byChannelID` | **1** |
| 0x05 | `bySID` | 0 |
| 0x06 | `byRes[6]` | zeros |

```
Control 0x7E9 with 12 B payload
poll command state
```

Expect brief frame disruption during correction.

---

## Auto-shutter enable (persistent policy)

| Layer | ID | Meaning |
|-------|---:|---------|
| DeviceConfig SET | **0x838** | Serial data transmission wrapper |
| DeviceConfig GET | **0x837** | Read wrapper |
| Inner opcode | **`dwDeviceCMD = 0x2001`** | Auto-shutter semantic |
| Inner value | **`dwValue`** | **0** = off, **1** = on |

### `USB_SYSTEM_SERIAL_DATA_TRANSMISSION` (~528 B host struct)

| Field | Value |
|-------|-------|
| `byMode` | **2** |
| `wDeviceCMDFlag` | **0** |
| `dwDeviceCMD` | **0x2001** |
| `dwValue` | 0 or 1 |

SET **0x838**, poll command state. Wire length follows the compact map for this struct (see command reference).

---

## Shutter calibration

Same **0x838** wrapper with different inner opcode:

| Field | Value |
|-------|-------|
| `dwDeviceCMD` | **0xF026** |
| `dwValue` | **2** |

Used in maintenance/calibration workflows, not every session.

---

## Shutter parameter GET/SET (0xC2D / 0xC2E)

Struct **`USB_SHUTTER_PARAM`** (~136 B) with level/min/max/default bytes. Available on the protocol but **not used** on the TC002C standard UI path.

---

## Burn protection

Burn-protection flags live in **image enhancement (0x7EB)**. Manual shutter is command **0x7E9**, separate from enhancement SETs.

---

## Decision table

| Action | Mechanism | Command |
|--------|-----------|---------|
| Manual shutter tap | Control | **0x7E9**, 12 B, channel 1 |
| Auto-shutter toggle | Serial wrapper | **0x838** + inner **0x2001** |
| Shutter calibration | Serial wrapper | **0x838** + inner **0xF026** |
| Read shutter levels | Optional | **0xC2D** |

---

## When to shutter

| Scenario | Recommendation |
|----------|----------------|
| Large ambient change | Manual **0x7E9** or wait for auto |
| After emissivity/range change | Allow 1–2 frames; optional manual |
| Periodic drift | Auto-shutter **0x2001** = 1 |

---

## Diagnostics

| Command | ID | Purpose |
|---------|---:|---------|
| GET diagnosed data | **0x7E8** | Diagnostic dump |
| GET device info | **0x7DB** | Firmware / model (488 B) |

---

## Teardown

Stop stream delivery before repeated shutter commands during logout. Complete or cancel in-flight bulk reads cleanly.
