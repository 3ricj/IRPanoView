# IRPanoView

I had to capture traffic from several USB thermal cameras for a project and ended up reverse-engineering a large part of the USB protocol behavior to make host streaming reliable.

This repository contains:

- a protocol documentation set for the TC002C Duo class camera
- implementation notes and references for closely related models (including TC001 thermal-only units)
- a demo Android app that can stream up to 4 USB cameras at once through a hub

The TC001 is thermal-only (the hardware I have), while TC002C also includes a visible camera path, but the protocol family appears broadly similar across these devices.

## Repository layout

- `src/` — app/source workspace for IRPanoView development
- `MasterThermoDocs/` — protocol reference for Hik/TopDon TC002C-class transport, streaming, and decode behavior

## Device identity and target

Device identity repeatedly reports model/device strings containing `Hik TM32-3RG/UIT`.
The thermal data path is Hik (`VID:PID 0x2BDF:0x0102`) with a `256x192` radiometric grid.

This may be useful to anyone working with low-cost `256x192` radiometric cameras, since many appear to be based on this chipset/protocol family.

## What is documented

### 1) USB transport + control plane

- UVC extension-unit style flow is documented (`SELECT / PROBE / GET / SET`).
- Command channels by `wValue` are mapped (`0x0100`, `0x0200`, `0x0300`, `0x0600`, `0x1700`).
- Session bind order, readiness gating, and post-SET status polling are documented.

### 2) Session initialization behavior

- Cold-plug behavior is documented, including 2-byte stub responses until proper bind.
- Required startup path is documented: bind -> ready check -> init config -> video param -> stream arm.
- Multi-camera guidance is included (bind by serial or bus+addr, not "first matching VID/PID").

### 3) Streaming + frame format

- Bulk IN stream path is documented (typically endpoint `0x81`) with UVC `FID/EOF` reassembly.
- Composite layout is documented in detail: radiometric band + footer + visible band + footer.
- The host stack uses a stable canonical decode contract for the assembled image payload.

### 4) Radiometric decode (temperature extraction)

- Per-pixel extraction format in the radiometric band is documented.
- Temperature math is documented:
  - add bias `0x37C0`
  - divide by `64`
  - subtract `273.15`
- Config encodings (ambient/emissivity/etc.) are clearly separated from stream pixel decode.

### 5) Control registers / command surfaces

- Thermometry basic params (`GET 0x7EE / SET 0x7EF`) are mapped, including key wire offsets.
- Emissivity, distance, ambient temperature, and range/gain representation is documented.
- Image controls are documented (`0x7EC/0x7ED`, `0x7EA/0x7EB`, `0x7E4/0x7E5`).
- Shutter + maintenance paths are documented (manual `0x7E9`, wrapper `0x838` with inner opcodes).
- Manual shutter/NUC trigger behavior and auto-shutter policy control are covered.

### 6) Identity + device info

- Device info blocks and field offsets are mapped (`0x7DB` required, `0x836` optional).
- Serial sources are compared (USB descriptor vs protocol fields), with host binding recommendations.

### 7) Lifecycle behavior (pause/resume/detach/force-close)

- Soft pause vs warm resume vs hard relaunch behavior is traced and documented.
- Resume semantics include replaying bind/init/stream setup, not just restarting bulk reads.

### 8) Command inventory coverage

- Priority command reference for the live path is documented.
- Extended catalog includes 200+ command kinds, with known-vs-unknown tracking.

## What is still open

- Full enums/bitfields for several advanced image/palette/ISP capability commands.
- Complete wire maps for some larger/extended structs.
- A few ambiguous command pairings/SET IDs in extended palette paths.
- Some less-used status/value semantics on this SKU.
- Stream recovery edge cases after manual NUC (exact manual sequence still needs more experimentation).

## Bottom line

This is a strong protocol-level USB/radiometry implementation guide: enough to build a working host stack (bind, configure, stream, decode temperatures, handle shutter + lifecycle), with remaining gaps mostly in advanced/extended features.

## Start here

- App/project notes: `src/README.md`
- Protocol index: `MasterThermoDocs/README.md`
