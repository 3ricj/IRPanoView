# 14 — Protocol known and unknown registry

Central registry of confirmed protocol facts vs open items for TC002C Duo (VID **0x2BDF**, PID **0x0102**). No discovery methodology — only what is documented vs what is not.

---

## Device identity and transport

| Item | Known | Unknown |
|------|-------|---------|
| USB VID/PID | **0x2BDF** / **0x0102** | — |
| Extension unit wIndex | Typically **0x0A00** | Alternate wIndex on other SKUs |
| UVC route for DeviceConfig | phase **(0x02, 0x05)**, wValue **0x0200** (GET) / **0x0300** (SET) | — |
| Bulk stream endpoint | **0x81** typical | Whether other endpoints carry format **0x67** on variants |
| Frame size | Canonical **200,704** B assembled UVC payload; jumbo **201,248** primary observed in restart windows | Full variant list across firmware builds |
| Stream format code | **0x67** | Other format codes on same hardware |

---

## Session initialization sequence

| Step | Known | Unknown |
|------|-------|---------|
| Bind / open | Required before DeviceConfig GETs | Exact timeout behavior |
| Cold-plug stub | Unbound device returns **2 B** `00 02` on GET **0x7DB** / **0x7DE** — not a parse failure | Whether stub persists after partial bind steps |
| Bind preamble | Init **0x1700** bursts (5 + **265 B**, ×4) → GET **0x7DE** → GET **0x7DB** → SELECT **(0x17,0x1D)** cap refresh (5 + **265 B**) | Minimum init burst count on all firmware builds |
| Bound-state gate | GET **0x7DE** ≥ **3 B** and not `00 02` prefix; do not trust PROBE **0x1700** u16=**512** alone on cold plug | All misleading PROBE values |
| Multi-camera bind | One session per device; key by USB serial or bus+addr | Stable `dwDevIndex` across replug on hub |
| **0x7DE** hardware server | Byte **[2]** must be **2** or **3** before stream | Meaning of other status values; SET behavior |
| Init SET order | **0x7ED** → **0x7EB** → **0x7EF** (standard path) | Whether order is strict on all firmware builds |
| **0x7ED** wire SET | **41** B | Full struct field map vs host 41 B |
| **0x7EB** wire SET | **176** B; `byPaletteMode=2` white-hot | Other palette mode values |
| **0x7EF** wire SET | **80** B from 260 B host struct | Padding/field packing rules for all 260 B fields |
| **0xBBC** stream param SET | fmt **0x67**, w=8, h=**0x3122**, fps=25 | Meaning of w/h fields beyond documented init values |
| Post-SET status poll | wValue **0x0600**, completion byte | Full state machine for failed SET |

---

## Device info and identity

See [15_Device_Info_And_Identity.md](15_Device_Info_And_Identity.md).

| Item | Known | Unknown |
|------|-------|---------|
| **0x7DB** wire size / route | **488 B**; SELECT **(0x01, 0x01)** @ **0x0100** | SET **0x7DC** behavior on TC002C |
| **0x7DB** cold stub | **2 B** `00 02` until bind complete | — |
| **0x7DB** wire map | 13 fields: firmware through `byDeviceClass`; **`bySerialNumber` @ wire 260** | Enum labels for `byDeviceAssembleType`, `byManufacturer`, `byLanguageType`, `byDeviceClass` |
| **0x7DB** packing | Wire = host bytes 4..491 (no `dwSize`, no `byRes`) | — |
| **0x836** wire size / route | **417 B**; SELECT **(0x01, 0x15)** @ **0x0100** | SET **0x837** wire map |
| **0x836** TC002C init | **Not** called at bind (optional GET) | — |
| **0x836** serial strings | `szDeviceSerialNumber`, `szMachineSerialNumber`, `szMechanismSerialNumber`, `szModel` populated | `szFirmwareIdentifyCode` binary layout |
| **0x827** / **0x83D** | Host capability-flag structs; routes **(0x01,0x11)** / **(0x01,0x16)** @ **0x0800**; wire GET **13 B** / **8 B** on authenticated session | Flag values on TC002C |
| **0x827** / **0x83D** libusb-only | May return **stale ASCII** (e.g. model string fragments) instead of u8 flags when bind incomplete | Whether full flag payload is reachable without SDK session object |
| USB descriptor serial | Short suffix (e.g. **9** chars on sample unit) | Whether OS always exposes full manufacturing string |
| USB vs **0x7DB** serial | **Match** on observationd unit (`EA6473497`) | — |
| **0x7DB** `byDeviceID` vs **0x836** `szDeviceSerialNumber` | Same composite string on sample unit | Internal string format rules |
| **0x838** disambiguation | **Command tunnel** for maintenance opcodes — **not** serial number storage | — |

---

| Offset / region | Known | Unknown |
|-----------------|-------|---------|
| Total payload size | Canonical **200,704** B (**256×392** YUYV, **512 B/row**) | Jumbo wire container variants (`201248` primary; nearby variants) and exact per-variant header semantics |
| Radiometric band | **0x000000**, **256×192** YUYV-like LE16 temp pairs | Macropixel field map beyond temp slots |
| Footer1 | **0x018000**, **4** rows — binary metadata | Field map |
| Visible band | **0x018800**, **256×192** grayscale YUYV (Y varies, U/V ≈ **0x80**) | LUT entry format on wire |
| Footer2 | **0x030800**, **4** rows — ASCII debug text | Full string catalog |
| Grid decode | `T_°C = u16/64 − 273.15` after **+0x37C0** bias per sample | Whether bias is always **0x37C0** on all gain modes |
| Preview vs radiometric sync | Same composite frame | Whether palette SET changes visible-band bytes on wire |
| UVC assembly | FID/EOF bulk IN, ~40 × ~5020 B transfers | — |
| Frame rate | **25** fps at init | Adjustable via command without re-init |

---

## Thermometry parameters (**0x7EE** / **0x7EF**)

| Field / topic | Known | Unknown |
|---------------|-------|---------|
| Host struct size | **260** B | — |
| Wire SET size | **80** B | Complete wire↔host field mapping for all 260 B |
| `byTemperatureRange` | Used for gain/range on TC002C | Full enum (values beyond those used at init) |
| Emissivity / distance / humidity encodings | Documented in [04](04_Thermometry_Parameters.md) | Valid ranges enforced by firmware |
| Reflection temperature | Field present in struct | Effect on grid when unset |
| **0x849** duplicate kind | Same struct tag in catalog | Relationship to **0x7EE** |

---

## Image and display

| Topic | Known | Unknown |
|-------|-------|---------|
| **0x7EB** `byPaletteMode` | Value **2** = white-hot at init | Full mode enum; labels |
| **0x843** palette upload | Up to **10,240** B LUT | LUT entry format; effect on stream |
| **0x847** multi-palette | Up to **20** palettes, GET | SET command ID; default factory set on TC002C |
| **0x820** enhancement EX | Struct defined | TC002C usage; wire sizes |
| **0x81B** / **0x858** capabilities | Commands exist | All bitfields and supported features |
| **0x853** YUV resolution | Command exists | Struct; whether SET changes **0x67** tail |
| Device vs host colorization | Both paths exist in protocol | Visual parity when same palette name selected |

See [12_Display_And_Palette.md](12_Display_And_Palette.md).

---

## Measurement and ROI

| Topic | Known | Unknown |
|-------|-------|---------|
| Grid dimensions | **256×192** | Sub-sampling rules if any |
| Spot / line / rect | Host-side over grid | Device-side ROI via **0x7FF** result layout |
| **0x7FF** ROI search | GET ~**516** B response | SET pairing; config struct |
| Expert regions **0x808** | Command exists | TC002C support |

---

## Shutter and maintenance

| Topic | Known | Unknown |
|-------|-------|---------|
| Manual correct **0x7E9** | Control API, **12** B, `byChannelID=1` | Timing relative to stream |
| **0x838** serial wrapper | Inner opcodes **0x2001**, **0xF026** documented | Full opcode table |
| Auto-shutter | Via **0x2001** inner SET | Default enabled state on TC002C |

---

## Command catalog completeness

| Topic | Known | Unknown |
|-------|-------|---------|
| Total DeviceConfig kinds | **207** in firmware template registry | Which subset TC002C implements |
| GET/SET pairing | +1 kind ID pattern common | Exceptions (e.g. **0x7FF**, multi-palette SET) |
| Wire payload sizes | Init P0 SETs, therm **80** B, enhancement **176** B | Most non-init commands |
| Capability cluster **0x81D–0x835** | IDs listed | All struct layouts |
| Non-thermal commands | Present in shared protocol | N/A on TC002C |

See [13_Extended_Command_Catalog.md](13_Extended_Command_Catalog.md).

---

## Enumerations and magic values (open)

| Enum / constant | Known values | Unknown values |
|-----------------|--------------|----------------|
| `byPaletteMode` | **2** (white-hot) | All others |
| `byTemperatureRange` | Init-used values in doc 04 | Full list |
| Hardware server status byte[2] | **2**, **3** = ready | **0**, **1**, others |
| `byVideoCodingType` (**0x7F6**) | Field exists | Valid codes |
| Inner serial opcodes (**0x838**) | **0x2001**, **0xF026** | Remainder |
| Stream format | **0x67** | Other formats on device |
| Noise reduce / ISP modes (**0x7EB**) | Fields exist | Valid ranges on TC002C |

---

## Error handling and timing

| Topic | Known | Unknown |
|-------|-------|---------|
| SET completion poll | **0x0600** status byte | Error codes in status |
| Command timeout | Host-dependent | Firmware-side timeout values |
| Stream without init | Fails or garbage | Exact failure mode |

---

## Cross-reference index

| Document | Scope |
|----------|-------|
| [03](03_Session_Initialization.md) | Init sequence (known steps) |
| [04](04_Thermometry_Parameters.md) | Therm struct (partial wire map) |
| [05](05_Image_Control.md) | Image SETs at init |
| [07](07_Frame_Format_And_Decode.md) | Frame layout and decode |
| [10](10_Command_Reference.md) | Priority command subset |
| [12](12_Display_And_Palette.md) | Display / YUV / palette |
| [13](13_Extended_Command_Catalog.md) | Full catalog by category |
| [15](15_Device_Info_And_Identity.md) | Device info GETs, wire maps, serial sources |
