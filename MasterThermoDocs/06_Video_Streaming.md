# 06 — Video streaming

Streaming couples a **DeviceConfig video parameter** with **bulk IN delivery** of UVC composite frames (**200,704 B** assembled payload per frame).

---

## Stream format token: 0x67

The TC002C path binds thermal composite streaming through SET **0xBBC**, not through thermal stream param **0x7F7**.

### `USB_VIDEO_PARAM` fields at stream start

| Field | Value | Meaning |
|-------|------:|---------|
| `dwVideoFormat` | **0x67** (103) | Thermal composite stream type |
| `dwWidth` | **8** | Format descriptor token |
| `dwHeight` | **0x3122** (12578) | Format descriptor token |
| `dwFramerate` | **0x19** (25) | 25 frames per second |

Width and height are protocol tokens, not pixel dimensions. The UVC composite raster is **256×392** YUYV (**512 B/row**); radiometric samples are in the first **256×192** band, visible preview in the second (see [07](07_Frame_Format_And_Decode.md)).

Stream delivery is armed with **stream type 0x67** matching the video format.

---

## Start sequence (after initConfig)

```
1. GET hardware server 0x7DE — confirm still ready
2. Poll command state
3. SELECT and negotiation GETs as required
4. SET video param 0xBBC
5. Poll command state
6. Arm stream delivery (type 0x67)
7. Bulk IN read loop → UVC payload assembly → 200704 B frames
```

Do not arm streaming before hardware-server status **≥ 2**.

---

## Bulk transfer and UVC assembly

| Property | Value |
|----------|-------|
| Direction | IN |
| Typical endpoint address | **0x81** |
| USB transfer size | ~**5020** B (includes 2-byte UVC header) |
| Payload per transfer | ~**5018** B |
| Transfers per frame | ~**40** |
| **Assembled frame payload** | **200,704 (0x31000)** |
| Nominal rate | **25 fps** (~4.8 MB/s) |

Resolve endpoint address from the USB configuration descriptor at stream open. Read with timeout **≥ 8000 ms** for the first frame after start.

Each bulk IN transfer begins with a **UVC payload header** (typically **2** bytes). Strip the header and append payload bytes. Emit one frame when **EOF** is set in `bmHeaderInfo`, on **FID** flip with buffered data, or at max assembly size. See [07_Frame_Format_And_Decode.md](07_Frame_Format_And_Decode.md) for the composite band layout inside the assembled payload.

Process each complete assembled frame before accepting the next bulk transfer if using synchronous I/O.

---

## Frame validation

| Assembled payload length | Action |
|--------------------------|--------|
| **200,704** | Valid composite frame — decode |
| Other | Partial assembly, resync, or error — retry or abort |

**Note:** Some host stacks or SDK callbacks may report **201,248** bytes when transport padding or misaligned slicing is included. The canonical image payload is **200,704** B — one **256×392** composite raster.

---

## Alternate: thermal stream param (0x7F6 / 0x7F7)

Struct **`USB_THERMAL_STREAM_PARAM`** (~20 B) with field `byVideoCodingType` exists for products that select coding type independently.

**TC002C Duo does not use this on the live path.** Format **0x67** is set exclusively via **0xBBC**.

---

## Stop / teardown

Full lifecycle (soft pause, resume/unpause, detach, force-close gaps): **[16_Pause_Resume_And_Lifecycle.md](16_Pause_Resume_And_Lifecycle.md)**.

**Soft pause (reference — back from thermal, camera plugged):**

```
stopStream → removeStreamCallback → USB_StopChannel (~10 ms)
Keep userId, fd, claims — no alt-0, release, close, or Logout
```

**Resume / unpause (reference — re-enter thermal, Scenario B):**

```
Same process: init/login skipped; bind + startStream again
Wire: ~72 control xfers (full re-bind class) + initConfig + SET 0xBBC + arm
~1.2 s from startStream to first 200704 B assembled frame (single cam, after UI nav)
```

**QuadView notes:** All leave paths (Exit, Compose dispose, `onDestroy` fallback) → reference pause via `pauseAll` / `referenceLeave` (cancel+join, keep fd). Resume skips re-claim when interfaces already held (`resume=keepClaims`). Recipe **E10** default; **E10D** adds alt-0 disarm for A/B fallback. See doc 16.

Evidence: teardown Scenario A trace notes

---

## Multi-camera bandwidth

Single stream ≈ **4.8 MB/s**. Four cameras on one USB3 controller require hub planning; each device needs an independent session and bulk reader.
