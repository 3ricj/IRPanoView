# 11 — Host implementation guide

Checklist for building a host that speaks the Hik USB thermal protocol directly over control and bulk transfers.

---

## Prerequisites

| Requirement | Detail |
|-------------|--------|
| USB access | Userspace driver binding with control + bulk |
| Exclusive device | Release other consumers before claiming interfaces |
| Frame contract | Decode contract is **200,704** B canonical composite; wire may also deliver jumbo restart payloads that normalize into this contract |

---

## Required host capabilities

1. USB control transfer read/write on extension unit **wIndex** (typically **0x0A00**)
2. USB bulk IN read on streaming endpoint (typically **0x81**)
3. GET-modify-SET state machine with command-state polling
4. UVC payload assembler (FID/EOF) and composite frame parser + temperature decode

---

## Constants

```
# USB identity
VID = 0x2BDF
PID = 0x0102

# UVC extension
WINDEX = 0x0A00          # confirm from device after bind
WVALUE_THERM = 0x0300
WVALUE_STATUS = 0x0600
WVALUE_SELECT = 0x0500

# Therm wire (80 B SET)
THERM_WIRE_LEN = 80
WIRE_OFF_OVERLAY = 2
WIRE_OFF_RANGE = 6
WIRE_OFF_EMISSIVITY = 16
WIRE_OFF_DISTANCE = 21

# Stream / composite frame
STREAM_FORMAT = 0x67
FRAME_BYTES = 200_704
JUMBO_FRAME_BYTES_PRIMARY = 201_248
ROW_BYTES = 512
RADIO_BASE = 0x0
ROW_BYTES = 512
RADIO_FIRST_ROW = 196
BULK_EP = 0x81
FPS = 25

# Decode
TEMP_BIAS = 0x37C0
TEMP_SCALE = 64
KELVIN_OFFSET = 273.15
GRID_W, GRID_H = 256, 192
HW_SERVER_READY = 3
```

---

## Transport interface

```
control_write(bm, breq, wvalue, windex, data)
control_read(bm, breq, wvalue, windex, length) -> bytes
bulk_read(endpoint, length, timeout_ms) -> bytes
```

---

## Encoding helpers

```
def encode_emissivity(ui):       return round(ui * 100)
def encode_distance_m(ui):       return round(ui * 100)
def encode_ambient_c(ui_c):        return round(ui_c * 100) + 10000

def decode_celsius(stored_u16):
    return stored_u16 / TEMP_SCALE - KELVIN_OFFSET

def celsius_from_raw(raw_u16):
    return decode_celsius((raw_u16 + TEMP_BIAS) & 0xFFFF)
```

---

## Therm SET payload (80 B)

Start from GET **0x7EE** baseline; patch fields:

```
payload[WIRE_OFF_RANGE] = temperature_range    # 2=Normal, 3=High
pack_u32_le(payload, WIRE_OFF_EMISSIVITY, emissivity)
pack_u32_le(payload, WIRE_OFF_DISTANCE, distance)

# OUT: bm=0x21 bReq=0x01 wVal=0x0300 wIdx=WINDEX len=80
poll_command_state()
```

---

## Session flow

```
protocol_init()
devices = enumerate_by_serial()   # never first-match when N > 1
session = bind(devices[chosen])   # full preamble: doc 03 Step 3

while hardware_server_status(session) < 2:
    sleep(0.15)

get_modify_set(0x7ED, patch_video_adjust)
get_modify_set(0x7EB, patch_enhancement)
get_modify_set(0x7EF, patch_therm)

set_video_param(format=0x67, width=8, height=0x3122, fps=25)
arm_stream(type=0x67)

frame = uvc_assemble_frame(BULK_EP)   # FID/EOF → canonical or jumbo wire payload
frame = normalize_if_jumbo(frame)     # always returns canonical 200704 B composite
assert len(frame) == FRAME_BYTES
t = celsius_from_raw(read_radio_u16(frame, x=128, y=96))
```

---

## Lifecycle (pause / resume)

See **[16_Pause_Resume_And_Lifecycle.md](16_Pause_Resume_And_Lifecycle.md)** for wire evidence.

| Phase | Host action |
|-------|-------------|
| Soft pause | Cancel bulk / StopChannel; **keep** fd + session; no alt-0, release, Logout |
| Resume | Re-run **full bind preamble + initConfig + SET 0xBBC + arm** (session may stay logged in) |
| Detach | Close fd; do not expect StopChannel after Logout |
| Force-close | No graceful teardown — next launch is cold init (doc 03) |

---

## Testing matrix

| Test | Pass criteria |
|------|---------------|
| Bind | GET **0x7DB** returns **488 B** (not **2 B** stub); GET **0x7DE** returns **3 B** |
| Ready gate | Hardware server status ≥ 2 within 30 s |
| initConfig | Three SETs complete; command poll idle |
| Stream | UVC assembly yields canonical 200704 directly or jumbo payloads that normalize to 200704 before decode |
| Decode | Center pixel plausible °C for indoor scene |
| Emissivity SET | GET reflects new wire emissivity |
| Range SET | `byTemperatureRange` toggles 2 ↔ 3 |
| Shutter | Control **0x7E9** completes without USB stall |
| Pause + resume | After soft pause, re-enter streams and sustain canonical decode (`200704`), including restart windows where jumbo wire payloads appear |

---

## Multi-camera

Each device needs:

- Independent session bind (full preamble per device)
- Independent bulk reader
- Unique USB path / serial at enumeration — **never** open “first matching VID/PID” when multiple units present

~**5 MB/s** per stream.

---

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Rejecting all `>200704` payloads | Classify jumbo (`201248` primary and close variants), normalize, then decode via canonical path |
| Grid at offset 0x1220 | Radiometry is YUYV-like LE16 temp pairs @ **0x0** (not flat u16 @ 0x18800) |
| Skipping +0x37C0 | Apply bias once when building grid |
| Double bias at measure | Measure uses **stored_u16** |
| Therm SET length 260 | Wire length is **80** |
| Gain via 0x7F1 | Use **0x7EF** `byTemperatureRange` |
| Stream before ready gate | Poll **0x7DE** until status ≥ 2 |
| Skipping bind preamble | Cold GET **0x7DB** returns **2 B** stub — run full bind (doc 03 Step 3) |
| First-match enumeration | With 2+ cameras, open by **serial** or **bus+addr** |
| Wrong protocol family | Verify canonical decode contract (**200704**) with jumbo normalization support, and **0x7EF** on **wValue 0x0300** |

---

## Document index

[README.md](README.md)
