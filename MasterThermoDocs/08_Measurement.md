# 08 — Measurement (spot, line, rectangle)

Live temperature readout uses the decoded **temperature grid** extracted from each frame. Optional firmware ROI commands exist but the standard TC002C path measures on the host grid.

---

## Grid contract

| Property | Value |
|----------|------:|
| Width | 256 |
| Height | 192 |
| Bytes | 98,304 (49,152 × u16 LE) |
| Content | **stored_u16** (includes +0x37C0 bias) |

Set grid dimensions to **256×192** before any measurement operation.

---

## Coordinate system

Row-major indexing:

```
pixel_index = y × 256 + x
byte_offset = 2 × pixel_index
stored_u16 = LE16(temp_grid[byte_offset : byte_offset+2])
T_°C = stored_u16 / 64.0 − 273.15
```

Map UI/touch coordinates to grid **(x, y)** accounting for display rotation. Typical landscape: **x** 0–255 horizontal, **y** 0–191 vertical.

---

## Point temperature

```
T = decode( temp_grid at (x, y) )
```

Optional neighborhood average for stability:

1. Extract N×N window around `(x, y)`
2. Drop min and max samples
3. Average remaining values
4. Apply `/64 − 273.15`

Center spot for QA: **(128, 96)**.

---

## Line temperature

Walk pixels along segment `(x0,y0)`–`(x1,y1)`, collect stored_u16 samples, return min, max, average with coordinates of extrema.

---

## Rectangle temperature

For inclusive rectangle `(x, y, w, h)`:

| Stat | Method |
|------|--------|
| Min | minimum stored_u16 → °C + coords |
| Max | maximum stored_u16 → °C + coords |
| Avg | mean stored_u16 (rounded) → °C |

---

## Firmware ROI alternate (0x7FF)

GET **0x7FF** (ROI temperature search) returns firmware-computed ROI stats (~516 B). Not used on the standard TC002C live measure path.

Region config **0x7F2/0x7F3** defines on-device ROI rules — optional, not required for host-side measure.

---

## Pseudo-color

Map each stored_u16 through `/64 − 273.15` to index a palette LUT. Measurement and colorization share the same °C domain.

---

## Multi-frame stability

Optional EMA filter on displayed Celsius. Shutter events may cause single-frame spikes.

---

## Pseudocode

```
import struct

def rect_stats(temp_grid, x0, y0, x1, y1):
    vals = []
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            off = 2 * (y * 256 + x)
            s = struct.unpack_from("<H", temp_grid, off)[0]
            vals.append(s / 64.0 - 273.15)
    return min(vals), max(vals), sum(vals) / len(vals)
```

---

## Checklist

- [ ] Grid is post-bias (no second +0x37C0)
- [ ] Resolution 256×192
- [ ] Touch mapping accounts for rotation
- [ ] Therm **0x7EF** params set before expecting physical accuracy
