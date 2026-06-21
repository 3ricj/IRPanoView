# Thermal frame wire format (IRPanoView)

## Live video (RTSP)

- **URL:** `rtsp://<pi>:8554/thermal` (default)
- H.264 hardware-encoded Jet false-color pano (default **1024×192** legacy; **variable W×H** when PTGUI warp calib loaded) @ 25 fps
- Low-latency: zero client caching; Pi uses leaky GStreamer queues

## Raw radiometry (TCP) — per-camera (unstitched)

- **Port:** 8767 (default)
- **Framing:** 8-byte header then IRPV packet
  - `uint32` magic `IRPR` (`0x52505249`)
  - `uint32` length (full IRPV packet size)
- **Rate:** configurable 0.1–1 Hz on Pi (`set_raw_emit_fps`)
- **Per tick the Pi sends one IRPV frame per camera** (`kCameraCount` frames),
  each a raw **256×192** tile tagged with `CAMERA_INFO` (slot + serial). All 4
  frames of a tick share the same `raw_emit_seq` (group sequence) so the client
  can align cameras. Stitching is done in post on the client side.

## Latency metadata (UDP)

- **Port:** 8768 (default), broadcast from Pi
- Fixed **76-byte** `IRPM` packet per video frame (see `pi/host/thermal_meta.h`)

## Control WebSocket (port 8766)

JSON messages, one object per text frame.

### Client → Pi

```json
{"cmd":"set_ir_config","emissivity":0.95,"distance_m":1.0,"ambient_c":22.0}
{"cmd":"trigger_nuc"}
{"cmd":"set_temporal_average","frames":3}
{"cmd":"set_raw_emit_fps","fps":1.0}
{"cmd":"set_display_range","floor_c":20,"ceiling_c":40,"auto":true}
{"cmd":"get_status"}
{"cmd":"get_latency_stats"}
```

### Pi → Client

```json
{"cmd":"status","cameras":[...],"compositor_hz":25.0,"raw_emit_fps":1.0,"rtsp_port":8554}
{"cmd":"latency_stats","compositor_hz":25.0,"video_seq":1234,"encoder_queue_bytes":0}
```

---

## IRPV v1 header (22 bytes)

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4 | Magic `IRPV` (`0x56505249`) |
| 4 | 1 | Version (`1`) |
| 5 | 1 | Flags (bit0=`PER_CAMERA_HEALTH`) |
| 6 | 2 | Width (variable; from stitch calib or legacy 1024) |
| 8 | 2 | Height (variable; from stitch calib or legacy 192) |
| 10 | 4 | Sequence (monotonic) |
| 14 | 8 | Timestamp microseconds (host clock) |

## IRPV v2 extension

- Version byte `2`
- Flags: bit0=`PER_CAMERA_HEALTH`, bit1=`HAS_META_V2`, bit2=`CAMERA_INFO`
- **72-byte metadata** after header, before payload:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 8 | `compose_us` |
| 8 | 8 | `emit_us` |
| 16 | 16 | `slot_frame_seq[4]` |
| 32 | 32 | `slot_usb_frame_us[4]` (0 if invalid) |
| 64 | 4 | `raw_emit_seq` (group seq; shared by a tick's 4 camera frames) |
| 68 | 4 | `flags` |

## Camera info block (when `CAMERA_INFO` flag set)

**20 bytes**, placed directly after the MetaV2 block and before the payload:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 1 | `slot` (1..`kCameraCount`) |
| 1 | 1 | `camera_count` (cameras in this group, e.g. 4) |
| 2 | 2 | reserved |
| 4 | 16 | `serial` (null-padded ASCII) |

Per-camera raw frames use flags `HAS_META_V2 | CAMERA_INFO` (no health trailer);
identity is carried by this block instead.

## Payload

`width × height × 2` bytes — little-endian **raw radiometric u16** per pixel (same semantics as Hik wire band before bias; viewer applies `+0x37C0` via `HikTherm`).

## Optional trailer (when `PER_CAMERA_HEALTH` flag set)

4 bytes — one byte per camera slot (1=streaming, 0=offline).

## Deprecated

UDP port **8765** high-rate IRPC/IRPV live stream is removed; use RTSP for live view and TCP for raw timelapse.
