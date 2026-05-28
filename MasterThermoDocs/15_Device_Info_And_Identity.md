# 15 — Device info and identity

Device identity on TC002C Duo is spread across USB enumeration, the bind-time **0x7DB** block, and optional description GETs. This document defines struct layouts, wire maps, and which serial field to use for host binding.

Cross-links: [02_USB_Transport](02_USB_Transport.md), [03_Session_Initialization](03_Session_Initialization.md), [09_Shutter_And_Maintenance](09_Shutter_And_Maintenance.md), [13_Extended_Command_Catalog](13_Extended_Command_Catalog.md), [14_Protocol_Known_And_Unknown](14_Protocol_Known_And_Unknown.md).

---

## Bind prerequisites (GET **0x7DB** / **0x836**)

Identity GETs require a **bound session**. On cold plug (replug, first open after power-on), GET **0x7DB** and GET **0x7DE** return a **2-byte stub** (`00 02`) until the host completes the bind preamble in [03_Session_Initialization](03_Session_Initialization.md) Step 3.

| Check | Unbound (stub) | Bound (OK) |
|-------|----------------|------------|
| GET **0x7DE** length | **2 B** | **3 B** |
| GET **0x7DB** length | **2 B** | **488 B** |
| PROBE **0x1700** u16 | **2** or **512** (misleading) | **5** or other small value after refresh |

Do not parse stub payloads as empty structs. Run the full bind sequence (init cap bursts → **0x7DE** → **0x7DB** → cap refresh) before reading identity fields.

**Multi-camera:** open and bind each unit by **USB serial** or **bus + address**; verify GET **0x7DB** `bySerialNumber` matches the chosen device.

---

## Overview — when each GET runs

| GET | Struct tag | When | TC002C init |
|-----|------------|------|-------------|
| **0x7DB** | `SYSTEM_DEVICE_INFO` | **Session bind** (required) | **Yes** — after init cap bursts and GET **0x7DE**; before stream-ready poll |
| **0x836** | `SYSTEM_DEVICE_DESCRIPTION_INFO` | Optional / diagnostics | **No** on standard Android bind path; **Yes** on direct libusb after bind |
| **0x827** | `SYSTEM_DEVICE_INFO_CAPABILITIES` | Capability query | **No** at bind |
| **0x83D** | `SYSTEM_DEVICE_DESCRIPTION_INFO_CAPABILITIES` | Capability query | **No** at bind |

For multi-camera hosts, use USB descriptor serial or **0x7DB** `bySerialNumber` at enumeration; both match on the reference unit (see § Serial numbers).

---

## GET **0x7DB** — `SYSTEM_DEVICE_INFO`

### Command and route

| Item | Value |
|------|-------|
| GET | **0x7DB** |
| SET | **0x7DC** (same struct; not used at TC002C bind) |
| SELECT | **(0x01, 0x01)** |
| Data **wValue** | **0x0100** |
| **wIndex** | **0x0A00** (typical) |
| Wire GET length | **488 B** |

### Host struct (516 B)

| Host offset | Size | Field |
|------------:|-----:|-------|
| 0 | 4 | `dwSize` |
| 4 | 64 | `byFirmwareVersion` |
| 68 | 64 | `byEncoderVersion` |
| 132 | 64 | `byHardwareVersion` |
| 196 | 64 | `byDeviceType` |
| 260 | 4 | `byProtocolVersion` |
| 264 | 64 | **`bySerialNumber`** |
| 328 | 64 | `bySecondHardwareVersion` |
| 392 | 32 | `byModuleID` |
| 424 | 64 | `byDeviceID` |
| 488 | 1 | `byDeviceAssembleType` |
| 489 | 1 | `byManufacturer` |
| 490 | 1 | `byLanguageType` |
| 491 | 1 | `byDeviceClass` |
| 492 | 24 | `byRes` |

### Wire map (488 B)

**Packing:** wire bytes = host offsets **4..491** (no `dwSize`, no `byRes`). `wire_offset = host_offset − 4`.

| Wire offset | Size | Host field | Encoding | Known on TC002C |
|------------:|-----:|------------|----------|-----------------|
| 0 | 64 | `byFirmwareVersion` | ASCII | `APP_209031_BUILD_20250917` |
| 64 | 64 | `byEncoderVersion` | ASCII | empty |
| 128 | 64 | `byHardwareVersion` | ASCII | `FPGA_010108_BUILD_20250414` |
| 192 | 64 | `byDeviceType` | ASCII | `FPGA UVC Camera` |
| 256 | 4 | `byProtocolVersion` | ASCII | `2.0` |
| **260** | **64** | **`bySerialNumber`** | ASCII | **`EA6473497`** |
| 324 | 64 | `bySecondHardwareVersion` | ASCII | empty |
| 388 | 32 | `byModuleID` | ASCII | `0953060200` |
| 420 | 64 | `byDeviceID` | ASCII | `TM32-3RG/UIT20251215AACHEA6473497` |
| 484 | 1 | `byDeviceAssembleType` | u8 | **0x01** |
| 485 | 1 | `byManufacturer` | u8 | **0x02** |
| 486 | 1 | `byLanguageType` | u8 | **0x01** |
| 487 | 1 | `byDeviceClass` | u8 | **0x02** |

### Known vs unknown (**0x7DB**)

| Known | Unknown |
|-------|---------|
| Wire **488 B**; compact prefix of 516 B host struct | Enum labels for `byDeviceAssembleType`, `byManufacturer`, `byLanguageType`, `byDeviceClass` |
| **`bySerialNumber`** @ wire **260** | Whether SET **0x7DC** is honored on TC002C |
| Firmware / FPGA / device-type strings populated | Meaning of `byModuleID` beyond opaque board code |
| `byDeviceID` carries full manufacturing string | |

---

## GET **0x836** — `SYSTEM_DEVICE_DESCRIPTION_INFO`

### Command and route

| Item | Value |
|------|-------|
| GET | **0x836** |
| SET | **0x837** |
| SELECT | **(0x01, 0x15)** |
| Data **wValue** | **0x0100** |
| Wire GET length | **417 B** (probe u16 **417**) |

### Host struct (1316 B)

| Host offset | Size | Field |
|------------:|-----:|-------|
| 0 | 4 | `dwSize` |
| 4 | 64 | **`szDeviceSerialNumber`** |
| 68 | 32 | **`szMachineSerialNumber`** |
| 100 | 32 | `szModel` |
| 132 | 32 | **`szMechanismSerialNumber`** |
| 164 | 64 | `szNUCParaVersion` |
| 228 | 64 | `szISPParaVersion` |
| 292 | 1 | `byConnectMode` |
| 293 | 3 | `byRes1` |
| 296 | 128 | `szFirmwareIdentifyCode` |
| 424 | 892 | `byRes` |

### Wire map (417 B)

| Wire offset | Size | Host field | Known on TC002C |
|------------:|-----:|------------|-----------------|
| 0 | 64 | `szDeviceSerialNumber` | `TM32-3RG/UIT20251215AACHEA6473497` |
| 64 | 32 | `szMachineSerialNumber` | `EA6473497` |
| 96 | 32 | `szModel` | `TM32-3RG/UIT` |
| 128 | 32 | `szMechanismSerialNumber` | `EA6473497` |
| 160 | 64 | `szNUCParaVersion` | `NUC Version 20250122` |
| 224 | 64 | `szISPParaVersion` | `ISP Version 22` |
| 288 | 1 | `byConnectMode` | **0x01** |
| 289 | 3 | `byRes1` | present |
| 292 | 125 | `szFirmwareIdentifyCode` | binary / mixed (not ASCII label) |

### Known vs unknown (**0x836**)

| Known | Unknown |
|-------|---------|
| Wire **417 B**; string fields populated on TC002C | `byConnectMode` enum |
| Not called during standard TC002C bind | Full **125 B** identify-code layout |
| Serial fields relate to **0x7DB** / USB descriptor (see below) | SET **0x837** wire map |

---

## Capabilities — **0x827** / **0x83D**

These GETs return **per-field u8 capability flags** (non-zero = field supported in description/info structs), not string payloads.

### **0x827** — `SYSTEM_DEVICE_INFO_CAPABILITIES`

| Item | Value |
|------|-------|
| GET | **0x827** |
| SELECT | **(0x01, 0x11)** |
| Data **wValue** | **0x0800** |
| Host struct size | **512 B** |

Flag fields (after `dwSize`): `byFirmwareVersion`, `byEncoderVersion`, `byHardwareVersion`, `byDeviceName`, `byProtocolVersion`, **`bySerialNumber`**, `bySecondhardwareVersion`, `byModuleID`, `byDeviceID`, `byDeviceAssembleType`, `byManufacturer`, `byLanguageType`, `byDeviceClass`.

### **0x83D** — `SYSTEM_DEVICE_DESCRIPTION_INFO_CAPABILITIES`

| Item | Value |
|------|-------|
| GET | **0x83D** |
| SELECT | **(0x01, 0x16)** |
| Data **wValue** | **0x0800** |
| Host struct size | **256 B** |

Flag fields: **`byDeviceSerialNumber`**, **`byMachineSerialNumber`**, `byModel`, **`byMechanismSerialNumber`**, `byNUCParaVersion`, `byISPParaVersion`, `byConnectMode`, `byFirmwareIdentifyCode`.

### Known vs unknown (capabilities)

| Known | Unknown |
|-------|---------|
| Struct layouts and flag field names | Bit semantics beyond “field exists” |
| Routes (SELECT sub-phase + **wValue 0x0800**) | Non-zero flag values for serial-related fields on TC002C |
| Wire GET lengths **13 B** / **8 B** on authenticated session | Full flag payload on libusb-only hosts (may return stale ASCII) |
| Not used at bind | — |

---

## Serial numbers — consolidated comparison

| Source | Command / layer | Field | Known on TC002C | Unknown |
|--------|-----------------|-------|-----------------|---------|
| USB enumeration | Standard USB string descriptor **`iSerialNumber`** | OS-visible serial string | **`EA6473497`** (matches **0x7DB**) | Full 16-char manufacturing prefix not always exposed by OS APIs |
| Device info GET | **0x7DB** | **`bySerialNumber`** | **`EA6473497`** @ wire **260** | — |
| Device info GET | **0x7DB** | `byDeviceID` | `TM32-3RG/UIT20251215AACHEA6473497` | Internal format spec |
| Device info GET | **0x7DB** | `byModuleID` | `0953060200` | Semantics |
| Description GET | **0x836** | **`szDeviceSerialNumber`** | Full composite (same as **0x7DB** `byDeviceID`) | — |
| Description GET | **0x836** | **`szMachineSerialNumber`** | `EA6473497` | — |
| Description GET | **0x836** | **`szMechanismSerialNumber`** | `EA6473497` | — |
| Description GET | **0x836** | `szModel` | `TM32-3RG/UIT` | — |
| Serial wrapper SET | **0x838** | inner opcodes | **Not serial storage** — command tunnel for maintenance (e.g. auto-shutter **0x2001**) | — |

**Descriptor vs 0x7DB:** On the observationd TC002C unit, USB string serial **matches** **`bySerialNumber`** (`EA6473497`). **`byDeviceID`** and **`szDeviceSerialNumber`** carry a longer manufacturing string that embeds the same suffix.

**Recommended host binding key:** USB descriptor serial or **0x7DB** `bySerialNumber` (equivalent on tested hardware). Use **0x836** only when the longer composite string or NUC/ISP labels are required.

---

## Distinction from **0x838** serial data transmission

SET **0x838** (`SYSTEM_SERIAL_DATA_TRANSMISSION`) is a **269 B command wrapper** for inner maintenance opcodes (auto-shutter, calibration, etc.). It does **not** read or write device serial number storage. See [09_Shutter_And_Maintenance](09_Shutter_And_Maintenance.md).

---

## Quick reference

| Need | Use |
|------|-----|
| Bind-time firmware / FPGA / model strings | GET **0x7DB** @ bind |
| Short serial for multi-camera key | USB descriptor or **0x7DB** `bySerialNumber` |
| Long manufacturing serial + NUC/ISP labels | GET **0x836** (optional) |
| Feature support bits | GET **0x827** / **0x83D** (optional) |
| Shutter / maintenance commands | SET **0x838** wrapper (not identity) |
