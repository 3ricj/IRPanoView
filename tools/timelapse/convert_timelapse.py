#!/usr/bin/env python3
"""Convert recorded IRPanoView radiometric frames into Jet-colored TIFs.

Reads the raw ``*-u16.bin`` payloads written by the viewer's RawTimelapseRecorder
(908x192 little-endian uint16, no header) and renders flicker-stable 8-bit RGB
TIFs for video assembly.

Key behavior:
  * Temperature math matches the live viewer (HikTherm): C = (raw + 0x37C0)/64 - 273.15
  * raw==0 pixels are treated as no-data (uncovered warp/edge) and excluded from
    the range statistics; they still render (clamped to the window floor color).
  * The display window (min/max Celsius) is derived from per-frame p1/p99 of the
    valid pixels, then heavily smoothed with an EMA so it drifts slowly instead of
    flickering frame-to-frame.
  * Output is a single-channel-equivalent RGB Jet image, lossless LZW TIF.

Future: swap the global linear window for a local/HDR tone map (placeholder kept
in ``map_to_display``); the Jet LUT and EMA plumbing stay the same.
"""

from __future__ import annotations

import argparse
import csv
import glob
import os
import sys
import time

import numpy as np
from PIL import Image

# --- Hik radiometric constants (must match viewer/.../Display/HikTherm.cs) ---
TEMP_BIAS_U16 = 0x37C0
TEMP_SCALE = 64.0
KELVIN_OFFSET_C = 273.15

DEFAULT_INPUT = r"C:\Users\ericj\OneDrive\Documents\thermal-test\timelapse-20260615-211625"


def celsius_from_raw(raw: np.ndarray) -> np.ndarray:
    """Vectorized raw-u16 -> Celsius, matching HikTherm.CelsiusFromRawU16."""
    stored = (raw.astype(np.int32) + TEMP_BIAS_U16) & 0xFFFF
    return stored / TEMP_SCALE - KELVIN_OFFSET_C


def build_jet_lut() -> np.ndarray:
    """256-entry uint8 RGB LUT matching ThermalColormap.Jet (viewer)."""
    t = np.arange(256, dtype=np.float64) / 255.0
    r = np.clip(1.5 - np.abs(4.0 * t - 3.0), 0.0, 1.0)
    g = np.clip(1.5 - np.abs(4.0 * t - 2.0), 0.0, 1.0)
    b = np.clip(1.5 - np.abs(4.0 * t - 1.0), 0.0, 1.0)
    lut = np.stack([r, g, b], axis=1) * 255.0
    return lut.astype(np.uint8)


def map_to_display(celsius: np.ndarray, win_min: float, win_max: float) -> np.ndarray:
    """Map Celsius to a 0..255 display index for the current window.

    Linear normalize+clamp for now. This is the hook where an HDR/local tone
    curve would later replace the straight linear mapping.
    """
    span = win_max - win_min
    if span <= 0:
        return np.zeros(celsius.shape, dtype=np.uint8)
    scaled = (celsius - win_min) / span * 255.0
    return np.clip(np.rint(scaled), 0, 255).astype(np.uint8)


def list_frames(input_dir: str) -> list[str]:
    files = sorted(glob.glob(os.path.join(input_dir, "*-u16.bin")))
    return files


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Convert IRPanoView radiometric frames to Jet TIFs.")
    ap.add_argument("--input", default=DEFAULT_INPUT, help="Folder of *-u16.bin frames")
    ap.add_argument("--output", default=None, help="Output folder (default: <input>/converted)")
    ap.add_argument("--skip", type=int, default=100, help="Process every Nth frame (default 100)")
    ap.add_argument("--start-index", type=int, default=100000, help="First CONVERTED_NNNNNN number")
    ap.add_argument("--width", type=int, default=1024)
    ap.add_argument("--height", type=int, default=192)
    ap.add_argument("--ema-alpha", type=float, default=0.05,
                    help="EMA weight for new per-frame percentiles (smaller = slower drift, less flicker)")
    ap.add_argument("--p-low", type=float, default=1.0, help="Low percentile for window min")
    ap.add_argument("--p-high", type=float, default=99.0, help="High percentile for window max")
    ap.add_argument("--min-span", type=float, default=2.0, help="Minimum window span in Celsius")
    ap.add_argument("--limit", type=int, default=0, help="Stop after this many outputs (0 = all)")
    ap.add_argument("--compression", default="tiff_lzw",
                    help="Pillow TIFF compression (tiff_lzw, none, ...)")
    args = ap.parse_args(argv)

    input_dir = args.input
    output_dir = args.output or os.path.join(input_dir, "converted")
    if not os.path.isdir(input_dir):
        print(f"Input folder not found: {input_dir}", file=sys.stderr)
        return 1
    os.makedirs(output_dir, exist_ok=True)

    npix = args.width * args.height
    frames = list_frames(input_dir)
    if not frames:
        print(f"No *-u16.bin frames found in {input_dir}", file=sys.stderr)
        return 1

    skip = max(1, args.skip)
    selected = frames[::skip]
    if args.limit > 0:
        selected = selected[: args.limit]

    print(f"Found {len(frames)} frames; processing {len(selected)} (skip={skip}).")
    print(f"Output -> {output_dir}  (CONVERTED_{args.start_index:06d}.tif ...)")

    lut = build_jet_lut()
    compression = None if args.compression.lower() == "none" else args.compression

    ema_min = None
    ema_max = None
    alpha = args.ema_alpha

    log_path = os.path.join(output_dir, "convert_log.csv")
    log = open(log_path, "w", newline="", encoding="utf-8")
    writer = csv.writer(log)
    writer.writerow(["out_name", "source", "p_low_c", "p_high_c", "win_min_c", "win_max_c", "valid_px"])

    out_n = args.start_index
    t0 = time.time()
    for i, src in enumerate(selected):
        raw = np.fromfile(src, dtype="<u2")
        if raw.size < npix:
            print(f"  skip {os.path.basename(src)}: {raw.size} px < {npix}", file=sys.stderr)
            continue
        raw = raw[:npix]
        celsius = celsius_from_raw(raw)

        valid = raw > 0
        vals = celsius[valid] if valid.any() else celsius
        p_lo = float(np.percentile(vals, args.p_low))
        p_hi = float(np.percentile(vals, args.p_high))
        if p_hi < p_lo:
            p_lo, p_hi = p_hi, p_lo

        # Heavily-smoothed EMA window (seeded on first frame so it doesn't ramp up).
        if ema_min is None:
            ema_min, ema_max = p_lo, p_hi
        else:
            ema_min = alpha * p_lo + (1.0 - alpha) * ema_min
            ema_max = alpha * p_hi + (1.0 - alpha) * ema_max

        win_min = ema_min
        win_max = ema_max
        if win_max - win_min < args.min_span:
            mid = 0.5 * (win_min + win_max)
            win_min = mid - args.min_span / 2.0
            win_max = mid + args.min_span / 2.0

        disp = map_to_display(celsius, win_min, win_max)
        rgb = lut[disp].reshape(args.height, args.width, 3)

        out_name = f"CONVERTED_{out_n:06d}.tif"
        out_path = os.path.join(output_dir, out_name)
        img = Image.fromarray(rgb, mode="RGB")
        if compression:
            img.save(out_path, format="TIFF", compression=compression)
        else:
            img.save(out_path, format="TIFF")

        writer.writerow([out_name, os.path.basename(src),
                         f"{p_lo:.3f}", f"{p_hi:.3f}",
                         f"{win_min:.3f}", f"{win_max:.3f}", int(valid.sum())])

        out_n += 1
        if (i + 1) % 25 == 0 or i + 1 == len(selected):
            rate = (i + 1) / max(1e-6, time.time() - t0)
            print(f"  {i + 1}/{len(selected)}  win={win_min:.2f}..{win_max:.2f}C  {rate:.1f} fps")

    log.close()
    print(f"Done. Wrote {out_n - args.start_index} TIFs. Log: {log_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
