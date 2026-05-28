# 07 — Frame format and temperature decode

Streaming uses a canonical **200,704-byte UVC composite payload** (**256×392 YUYV**, **512 B/row**) for decode/render, but restart windows may deliver a second on-wire shape ("jumbo") that must be normalized before decode.

- **Canonical wire payload:** `200704` (`0x31000`)
- **Jumbo wire payloads:** `201248` (primary), with nearby observed variants (`201256`, `201258`)

---

## Composite layout

Two **256×192** image bands (**98,304** bytes each) separated by **512-byte** footer rows (4 composite rows = **0x800**).

```
Offset (hex)   Size (hex)   Content
──────────────────────────────────────────────────────────
0x000000       0x018000     radiometric — YUYV-like LE16 temp pairs (192 rows)
0x018000       0x000800     footer1 — binary metadata (4 rows)
0x018800       0x018000     visible — LUT grayscale YUYV (192 rows)
0x030800       0x000800     footer2 — ASCII diagnostics (4 rows)
──────────────────────────────────────────────────────────
Total          0x031000     200,704 B
```

```
┌──────── radiometric 256×192 YUYV-like temp pairs ────┐  rows   0–191
├────────────── footer1 (4 rows) ──────────────────────┤  rows 192–195
├──────── visible 256×192 grayscale YUYV (Y varies) ───┤  rows 196–387
└────────────── footer2 (4 rows, ASCII) ───────────────┘  rows 388–391
```

| Constant | Value |
|----------|------:|
| Frame payload size | **200,704** (0x31000) |
| Width | **256** pixels |
| Image band height | **192** rows |
| Composite height | **392** rows (192 + 4 + 192 + 4) |
| Row stride | **512** bytes (256 × 2) |
| Radiometric offset | **0x000000** |
| Footer1 offset | **0x018000** (4 rows = **0x800**) |
| Visible offset | **0x018800** |
| Footer2 offset | **0x030800** (4 rows = **0x800**) |

Slice helpers:

```python
radio   = data[0x000000:0x018000]   # radiometric band
footer1 = data[0x018000:0x018800]   # 4 rows — binary metadata
visible = data[0x018800:0x030800]   # LUT grayscale YUYV (192 rows)
footer2 = data[0x030800:0x031000]   # 4 rows — ASCII diagnostics
```

---

## Alternate jumbo wire layout (restart artifact)

When jumbo payloads are observed, they are treated as an alternate wire container for the same two image planes:

```
Offset (hex)   Size (hex)   Content
──────────────────────────────────────────────────────────
0x000000       ~0x001220    jumbo header / prefix (variable across variants)
0x001220       0x018000     temp plane (98304 B)
tail-0x018000  0x018000     yuv plane  (98304 B, at end of payload)
──────────────────────────────────────────────────────────
Total          0x031220     201,248 B (primary observed)
```

Normalization to canonical `200704`:

- canonical `radio` (`0x000000..0x017FFF`)  <- jumbo temp plane
- canonical `visible` (`0x018800..0x0307FF`) <- jumbo tail yuv plane
- canonical `footer1/footer2` rows are zero-filled (jumbo does not provide footer payload semantics)

The decoder path remains single-path after normalization.

---

## UVC bulk assembly

Bulk IN does **not** return one fixed-size read per frame. Each USB transfer is a **UVC payload packet**:

| Property | Typical value |
|----------|---------------|
| Direction | IN |
| Endpoint | **0x81** |
| UVC header | **2+** bytes at start of each transfer |
| `bmHeaderInfo` bits | **FID** (0x01), **EOF** (0x02) |
| Payload per transfer | ~**5018** B (transfer size ~**5020** B minus header) |
| Transfers per frame | ~**40** |
| Assembled payload on **EOF** | **200,704** canonical, or jumbo variants (`201248` primary) |

Assembly (libuvc-style):

1. Strip the UVC header from each bulk IN transfer.
2. Append payload bytes to a reassembly buffer.
3. On **FID** toggle with data already buffered, or on **EOF**, or at max size — emit one frame.
4. Accept either canonical **200,704** or recognized jumbo sizes; normalize jumbo to canonical **200,704** before decode.
5. Optionally trim a **2-byte** wire leader **`0x73 0x77`** if present at payload start (transport prefix, not part of the composite raster).

Do not rely on fixed-size USB reads. Trust **FID/EOF** assembly plus size classification (canonical vs jumbo).

Stream delivery and arming: [06_Video_Streaming.md](06_Video_Streaming.md).

---

## Radiometric band format

The **radiometric** slice (**0x000000 – 0x017FFF**) occupies composite rows **0–191**. Temperature samples are packed in a **YUYV-like macropixel layout**: **4 bytes** per **2** horizontal pixels, each pair holding a **little-endian u16** raw temperature count.

| Property | Value |
|----------|------:|
| Byte range | **0x000000 – 0x017FFF** |
| Geometry | **256 × 192** samples |
| Row stride | **512** B |
| Macropixel width | **4** B per **2** pixels |
| Encoding | LE16 temp in YUYV slot positions (not display YUV) |

For pixel `(x, y)` where `x ∈ [0,255]`, `y ∈ [0,191]`:

```
mac = 0x000000 + y × 512 + (x // 2) × 4

if x is even:
    raw_u16 = LE16( frame[mac : mac+2] )
else:
    raw_u16 = LE16( frame[mac+2 : mac+4] )
```

Init configures **256×192 landscape**. Display rotation may swap view coordinates; decode uses row-major **x ∈ [0,255], y ∈ [0,191]** unless the host explicitly rotates the grid.

---

## Stage 1 — Extract raw u16

Use the macropixel rule above on the radiometric band. These are firmware-scaled radiometric counts before host bias.

```python
def raw_u16_at_pixel(frame: bytes, x: int, y: int) -> int:
    mac = y * 512 + (x // 2) * 4
    if x % 2 == 0:
        return frame[mac] | (frame[mac + 1] << 8)
    return frame[mac + 2] | (frame[mac + 3] << 8)
```

---

## Stage 2 — Apply storage bias

Before Kelvin conversion, add a fixed bias:

```
stored_u16 = (raw_u16 + 0x37C0) mod 65536
```

| Constant | Decimal | Role |
|----------|--------:|------|
| **0x37C0** | 14,272 | Bias applied once when building the temperature grid |

Apply **+0x37C0** exactly once per pixel when extracting from the radiometric band.

---

## Stage 3 — Convert to Celsius

```
T_Kelvin = stored_u16 / 64.0
T_Celsius = T_Kelvin − 273.15
```

Combined:

```
T_Celsius = (raw_u16 + 0x37C0) / 64.0 − 273.15
```

Shortcut:

```
T_Celsius = raw_u16/64 − 50.15
```

| Constant | Value |
|----------|------:|
| Scale | **64 (0x40)** |
| Kelvin offset | **273.15** |

---

## Worked examples

| raw_u16 | stored_u16 | T_K (÷64) | T_°C |
|--------:|-----------:|----------:|-----:|
| 0 | 14,272 | 223.0 | **−50.15** |
| 4,810 | 19,082 | 298.16 | **≈ 25.0** |
| 6,400 | 20,672 | 323.0 | **≈ 49.85** |

Target **25.0 °C** → stored_u16 ≈ 19,081.6 → raw_u16 ≈ **4,810**.

---

## Visible band (preview / LUT)

| Property | Value |
|----------|-------|
| Location | **0x018800 – 0x0307FF** |
| Composite rows | **196–387** |
| Size | **98,304** B (**512 B/row**) |
| Format | YUYV macropixel stream |
| Content | **Grayscale / LUT** — **Y** (luma) varies; **U** and **V** ≈ **0x80** (neutral chroma) |

The visible band is device-produced preview data (palette/LUT applied on-device). It is independent of the radiometric band — not derived on-host from temperature counts in the wire format. Display appearance may also be controlled via palette commands (**0x7EB**, **0x839/0x843**, **0x847**).

Full display and palette detail: [12_Display_And_Palette.md](12_Display_And_Palette.md).

**Note:** Host-side pseudo-color from **stored_u16** (post-bias, `/64 − 273.15`) is an alternative to using the visible band; it is not the same bytes as the device preview plane.

---

## Footer bands

| Band | Offset | Size | Content |
|------|--------|------|---------|
| **footer1** | **0x018000** | **0x800** (4 rows) | Binary metadata — field map not fully documented |
| **footer2** | **0x030800** | **0x800** (4 rows) | Printable **ASCII** sensor debug strings (e.g. `[TEMP_UPDATE] Temp of CAV is …`) |

Footer rows use the same **512 B/row** stride as image bands.

---

## Building the temperature grid

```python
BIAS = 0x37C0
temp_grid = [[0] * 256 for _ in range(192)]

for y in range(192):
    for x in range(256):
        raw = raw_u16_at_pixel(frame, x, y)
        temp_grid[y][x] = (raw + BIAS) & 0xFFFF

visible_yuyv = frame[0x018800:0x030800]
debug_text   = ascii_printable(frame[0x030800:0x031000])
```

Spot/line/rect measurement reads **stored_u16** from `temp_grid` — **do not apply bias twice**.

---

## Pseudocode

```
FRAME_BYTES = 200704
RADIO_BASE = 0x0
ROW_BYTES = 512
BIAS = 0x37C0
SCALE = 64
K_OFFSET = 273.15

def raw_u16_at_pixel(frame, x, y):
    assert len(frame) == FRAME_BYTES
    mac = RADIO_BASE + y * ROW_BYTES + (x // 2) * 4
    if x % 2 == 0:
        return u16_le(frame, mac)
    return u16_le(frame, mac + 2)

def celsius_at_pixel(frame, x, y):
    raw = raw_u16_at_pixel(frame, x, y)
    return (raw + BIAS) / SCALE - K_OFFSET
```

---

## Relationship to 0x7EF ambient encoding

| Context | Encoding |
|---------|----------|
| Config ambient (`dwEnviromentTemperature`) | `(UI°C × 100) + 10000` |
| Stream grid pixel | `(raw_u16 + 0x37C0) / 64 − 273.15` |

Never feed ambient config values directly into pixel decode.

---

## Firmware radiometry

Emissivity, distance, ambient, and range on **0x7EF** influence the **raw_u16** values firmware writes into the radiometric band. The host decode formula is fixed; changing environment params changes subsequent `raw_u16`, not the conversion equation.

---

## Validation checklist

- [ ] Assembled UVC payload length is canonical **200,704** or recognized jumbo (`201248` primary; nearby variants accepted when shape matches)
- [ ] Jumbo payloads normalize to canonical **200,704** before decode
- [ ] Radiometric band at **0x000000**, YUYV-like LE16 temp pairs, **256×192**
- [ ] Visible band at **0x018800**, Y varies, U/V ≈ **0x80**
- [ ] Bias **0x37C0** applied once
- [ ] Scale **64**, offset **273.15**
- [ ] Indoor scene center roughly **15–35 °C** after therm SET
- [ ] **footer2** contains plausible ASCII debug text on live stream
