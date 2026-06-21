# PTGUI stitch calibration tools

Offline pipeline to turn a PTGUI `.pts` project into a Pi host warp bundle.

## Setup

```bash
pip install -r tools/stitch/requirements.txt
```

## Usage

```bash
py -3 tools/stitch/irpv_stitch_calibrate.py "eq-samples/EA6462986 Panorama.pts" \
  --out stitch-calib --images eq-samples
```

Outputs in `stitch-calib/`:

| File | Purpose |
|------|---------|
| `calib.json` | Parsed lens/pose metadata, output size, serial order |
| `warp_lut.bin` | Per-pixel u16 remap + feather weights (runtime stitch) |
| `eq_seam_samples.bin` | Overlap sample pairs for radiometric equalizer |
| `preview.jpg` | Offline RGB sanity check (eq-sample JPGs only) |

Deploy to Pi: `~/stitch-calib/` then restart host (auto-loads) or:

```bash
irpanoview-host --stitch-calib ~/stitch-calib --stitch-mode warp
```

## Re-calibration (mount change)

1. Stop host
2. `irpanoview-eq-samples --out ~/eq-samples`
3. PTGUI align → save new `.pts`
4. Run `irpv_stitch_calibrate.py` on dev machine
5. Copy `stitch-calib/` to Pi, restart host

## Radiometric pipeline

All runtime stitching is on **raw u16** thermal tiles from USB. Jet/LUT false-color is applied only for RTSP display **after** the pano is composed. The preview JPEG here uses RGB eq-samples for human review only.

Equalization runs **before** warp: EMA bias is estimated from u16 pairs at LUT-defined overlap coordinates (`eq_seam_samples.bin`), applied to whole tiles, then warp/feather produces the u16 pano.
