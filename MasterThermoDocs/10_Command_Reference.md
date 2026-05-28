# 10 — Command reference (priority subset)

DeviceConfig command catalog for **TC002C Duo** live streaming and thermometry. GET ids are even; SET = GET + 1 unless noted.

**Legend:** P0 = required every session; P1 = user settings; P2 = optional / unused on TC002C standard path.

---

## Session and gate

| P | GET | SET | Name | Wire notes |
|---|----:|----:|------|------------|
| P0 | — | — | Protocol init | Once per process |
| P0 | — | — | Session bind | After enumeration |
| P0 | **0x7DE** | — | System hardware server | 3 B; byte[2] = readiness |
| P0 | **0x7DB** | — | System device info | **488 B** at bind — [15](15_Device_Info_And_Identity.md) |
| P2 | **0x836** | — | System device description info | **417 B** optional identity GET — [15](15_Device_Info_And_Identity.md) |

---

## Image (wValue 0x0200)

| P | GET | SET | Name | Wire SET |
|---|----:|----:|------|----------|
| P0 | 0x7EC | **0x7ED** | Image video adjust | 41 B |
| P0 | 0x7EA | **0x7EB** | Image enhancement | 176 B |
| P1 | 0x7E4 | **0x7E5** | Image contrast | compact |
| P2 | 0xC2D | 0xC2E | Image shutter param | ~136 B |

---

## Thermometry (wValue 0x0300)

| P | GET | SET | Name | Wire |
|---|----:|----:|------|------|
| P0 | **0x7EE** | **0x7EF** | Thermometry basic param | **80 B** |
| P2 | 0x7F0 | 0x7F1 | Thermometry mode | ~70 B — unused TC002C |
| P2 | 0x7F2 | 0x7F3 | Thermometry regions | ~556 B |
| P2 | 0x7F8 | 0x7F9 | Temperature correct | unused |
| P2 | 0x80F | 0x810 | Environment temp correct | unused |
| P2 | 0x7FA | 0x7FB | Black body | unused |

---

## Stream

| P | GET | SET | Name | Notes |
|---|----:|----:|------|-------|
| P0 | 0xBBB | **0xBBC** | Video param | fmt **0x67**, 25 fps |
| P0 | — | — | Stream delivery | UVC composite **200704** B |
| P2 | 0x7F6 | 0x7F7 | Thermal stream param | unused TC002C |
| P2 | 0x853 | 0x854 | YUV resolution | catalog |

---

## Control (non-DeviceConfig)

| P | Control ID | Name | Payload |
|---|----------:|------|---------|
| P1 | **0x7E9** | Image manual correct (shutter) | 12 B `USB_COMMON_COND` |
| P0 | — | Command state poll | wValue **0x0600**, 1 B |

---

## Serial wrapper (0x838)

| P | GET | SET | Inner `dwDeviceCMD` | Purpose |
|---|----:|----:|--------------------:|---------|
| P1 | 0x837 | **0x838** | **0x2001** | Auto-shutter on/off |
| P1 | 0x837 | **0x838** | **0xF026** | Shutter calibration |

---

## Measure (optional)

| P | GET | SET | Name | Notes |
|---|----:|----:|------|-------|
| P2 | **0x7FF** | — | ROI temperature search | ~516 B result |

---

## Capabilities

| P | Route | wValue | Len | Purpose |
|---|-------|--------|----:|---------|
| P0 | (none) ×4 | **0x1700** | 5 + **265** | Init capability bursts (protocol init) |
| P0 | SELECT (0x17,0x1D) | **0x1700** | 5 + **265** | Bind-path capabilities refresh |

---

## UVC transport quick reference

| wValue | Channel |
|--------|---------|
| 0x0100 | Misc / server / device info |
| 0x0200 | Image params |
| 0x0300 | Therm basic |
| 0x0500 | SELECT arm |
| 0x0600 | Command status |
| 0x1700 | Capabilities |

| bmRequestType | bRequest | Direction |
|---------------|----------|-----------|
| 0x21 | 0x01 | OUT (SET) |
| 0xA1 | 0x81 | IN (GET data) |
| 0xA1 | 0x85 | IN (PROBE) |

---

## Struct size cheat sheet

| Struct | Host size | Wire SET |
|--------|----------:|---------:|
| `USB_THERMOMETRY_BASIC_PARAM` | 260 | **80** |
| `USB_IMAGE_VIDEO_ADJUST` | ~44 | **41** |
| `USB_IMAGE_ENHANCEMENT` | larger | **176** |
| `USB_COMMON_COND` | 12 | **12** |
| `USB_SYSTEM_SERIAL_DATA_TRANSMISSION` | 528 | compact map |

Always use **wire** length on USB control transfers.

---

## Extended catalog and open items

| Document | Contents |
|----------|----------|
| [13_Extended_Command_Catalog.md](13_Extended_Command_Catalog.md) | **207** command kinds by category; thermal vs non-thermal |
| [12_Display_And_Palette.md](12_Display_And_Palette.md) | Visible band, palette structs, display known/unknown |
| [14_Protocol_Known_And_Unknown.md](14_Protocol_Known_And_Unknown.md) | Master registry — enums, wire sizes, capability gaps |

This file lists only the **TC002C Duo streaming priority subset**. Expert calibration, black body, body-temp compensation, and capability cluster **0x81D–0x835** are documented in the extended catalog with explicit unknown fields.
