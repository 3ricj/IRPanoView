# MasterThermoDocs

Protocol documentation for the **Hik USB thermal camera protocol** used by TopDon TC002C Duo and related models. Covers session setup, device control, video streaming, and radiometric decode.

**Target devices:** TC002C Duo (USB **VID 0x2BDF** / **PID 0x0102**). Sibling products using the same command set and frame format follow the same rules.

---

## Document map

| Doc | Topic |
|-----|--------|
| [01_Overview.md](01_Overview.md) | Architecture and protocol planes |
| [02_USB_Transport.md](02_USB_Transport.md) | UVC extension transport, SELECT/PROBE/GET/SET |
| [03_Session_Initialization.md](03_Session_Initialization.md) | Bind, readiness gate, initConfig, first frame |
| [04_Thermometry_Parameters.md](04_Thermometry_Parameters.md) | Therm basic param struct, encodings, wire map |
| [05_Image_Control.md](05_Image_Control.md) | Orientation, palette, contrast, enhancement |
| [06_Video_Streaming.md](06_Video_Streaming.md) | Video format 0x67, bulk IN, frame rate |
| [07_Frame_Format_And_Decode.md](07_Frame_Format_And_Decode.md) | UVC composite frame layout, assembly, and °C math |
| [08_Measurement.md](08_Measurement.md) | Grid coordinates, spot/line/rect readout |
| [09_Shutter_And_Maintenance.md](09_Shutter_And_Maintenance.md) | Manual NUC, auto-shutter, serial commands |
| [10_Command_Reference.md](10_Command_Reference.md) | Priority DeviceConfig subset (streaming path) |
| [11_Host_Implementation_Guide.md](11_Host_Implementation_Guide.md) | Host checklist, constants, pseudocode |
| [12_Display_And_Palette.md](12_Display_And_Palette.md) | Visible band, palette commands, display known/unknown |
| [13_Extended_Command_Catalog.md](13_Extended_Command_Catalog.md) | Full command inventory by category |
| [14_Protocol_Known_And_Unknown.md](14_Protocol_Known_And_Unknown.md) | Master registry of confirmed vs open items |
| [15_Device_Info_And_Identity.md](15_Device_Info_And_Identity.md) | Device info GETs, wire maps, serial number sources |
| [16_Pause_Resume_And_Lifecycle.md](16_Pause_Resume_And_Lifecycle.md) | Soft pause, resume/unpause, detach, force-close gaps |

---

## Quick start (minimal happy path)

1. Open USB device; typical extension unit **wIndex = 0x0A00**.
2. **Bind session:** init cap bursts → GET **0x7DE** → GET **0x7DB** (488 B) → cap refresh — see [03](03_Session_Initialization.md) Step 3. Cold plug returns **2 B** stub until bind completes.
3. **Wait** until hardware-server `byDeviceInitialStatus` is **2** or **3** (ready to stream).
4. **initConfig:** GET/modify/SET image video adjust (**0x7ED**), image enhancement (**0x7EB**), therm basic param (**0x7EF**).
5. **Stream:** SET video param (**0xBBC**, format **0x67**, 25 fps) then start stream delivery.
6. **Bulk IN:** assemble UVC payload packets from endpoint **0x81** (typical) → **200,704** B composite frame on EOF.
7. **Decode:** radiometric band **0x000000–0x017FFF** — YUYV-like LE16 temp pairs; add bias **0x37C0**, convert `T_°C = stored_u16 / 64 − 273.15`.

---

## Conventions

- Multi-byte integers are **little-endian** unless noted.
- **GET** command IDs are even (e.g. **0x7EE**); matching **SET** is GET+1 (e.g. **0x7EF**).
- Full host struct sizes may differ from **on-wire payload** sizes; always use the wire length documented per command.
- After each SET, poll **command state** (status byte at **wValue 0x0600**) until the device completes the operation.
