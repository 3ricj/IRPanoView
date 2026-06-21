#!/usr/bin/env python3
"""Tune pose offsets to match a PTGUI-exported reference panorama."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from scipy import optimize

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from bake_stitch_luts import bilinear_sample_u8, reorder_by_slot, serial_to_slot
from panotools_math import bake_warp_maps
from parse_ptgui_pts import calib_from_dict, parse_pts

try:
    from PIL import Image
except ImportError:
    Image = None  # type: ignore


def load_gray(path: Path, width: int, height: int) -> np.ndarray:
    img = np.array(Image.open(path).convert("L").resize((width, height), Image.Resampling.BILINEAR))
    return img.astype(np.float64) / 255.0


def render_preview(
    images_dir: Path,
    serials: list[str],
    width: int,
    height: int,
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
) -> np.ndarray:
    num_cams = len(serials)
    imgs = []
    for serial in serials:
        imgs.append(np.array(Image.open(images_dir / f"{serial}.jpg").convert("RGB")))
    out = np.zeros((height, width), dtype=np.float64)
    for row in range(height):
        for col in range(width):
            acc = 0.0
            wsum = 0.0
            for ci in range(num_cams):
                w = weights[ci, row, col]
                if w <= 0:
                    continue
                sx = float(src_x[ci, row, col])
                sy = float(src_y[ci, row, col])
                if sx < 0:
                    continue
                r, g, b = bilinear_sample_u8(imgs[ci], sx, sy)
                acc += (0.299 * r + 0.587 * g + 0.114 * b) * w
                wsum += w
            if wsum > 0:
                out[row, col] = acc / wsum
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description="Tune calib pose offsets against PTGUI reference JPG")
    ap.add_argument("pts", type=Path)
    ap.add_argument("--reference", type=Path, required=True, help="PTGUI exported panorama JPG")
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--out", type=Path, help="Write tuned calib.json here")
    args = ap.parse_args()

    if Image is None:
        raise SystemExit("Pillow required")

    ref_img = Image.open(args.reference)
    width, height = ref_img.size
    ref = load_gray(args.reference, width, height)

    calib = parse_pts(args.pts)
    serials_sorted = sorted(calib.cameras.keys())
    n = len(serials_sorted)

    def score(params: np.ndarray) -> float:
        for i, serial in enumerate(serials_sorted):
            calib.cameras[serial].yaw_offset_deg = float(params[i])
            calib.cameras[serial].pitch_offset_deg = float(params[n + i])
        src_x, src_y, weights, serials = bake_warp_maps(calib, width, height)
        slot_serials, src_x, src_y, weights, _ = reorder_by_slot(serials, src_x, src_y, weights)
        pred = render_preview(args.images, slot_serials, width, height, src_x, src_y, weights)
        mask = ref > 0.02
        if mask.sum() < 100:
            return 1.0
        diff = pred[mask] - ref[mask]
        return float(np.mean(diff * diff))

    x0 = np.zeros(2 * n)
    for i, serial in enumerate(serials_sorted):
        x0[i] = calib.cameras[serial].yaw_offset_deg
        x0[n + i] = calib.cameras[serial].pitch_offset_deg

    print(f"Tuning to reference {args.reference.name} ({width}x{height}), baseline mse={score(x0):.5f}")
    res = optimize.minimize(
        score,
        x0,
        method="Nelder-Mead",
        options={"maxiter": 80, "xatol": 0.05, "fatol": 1e-5},
    )
    print(f"Optimized mse={res.fun:.5f}")
    for i, serial in enumerate(serials_sorted):
        y = float(res.x[i])
        p = float(res.x[n + i])
        calib.cameras[serial].yaw_offset_deg = y
        calib.cameras[serial].pitch_offset_deg = p
        print(f"  {serial} yaw_off={y:+.3f} pitch_off={p:+.3f}")

    if args.out:
        import json
        from parse_ptgui_pts import calib_to_dict

        args.out.parent.mkdir(parents=True, exist_ok=True)
        data = calib_to_dict(calib)
        data["output_width"] = width
        data["output_height"] = height
        args.out.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
