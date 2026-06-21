#!/usr/bin/env python3
"""Validate PTGUI control points against baked calibration."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from panotools_math import bake_warp_maps, project_camera_pixel_to_pano
from parse_ptgui_pts import calib_from_dict, parse_pts


def ptgui_index_to_serial(calib, index: int) -> str:
    serials = sorted(calib.cameras.keys(), key=lambda s: calib.cameras[s].ptgui_index)
    by_idx = {calib.cameras[s].ptgui_index: s for s in calib.cameras}
    if index in by_idx:
        return by_idx[index]
    if 0 <= index < len(serials):
        return serials[index]
    raise KeyError(f"unknown ptgui index {index}")


def validate_control_points(calib, width: int, height: int, mean_threshold: float, max_threshold: float) -> bool:
    errors = []
    seen = set()
    for cp in calib.control_points:
        if cp.get("t", 0) != 0:
            continue
        if "0" not in cp or "1" not in cp:
            continue
        a = cp["0"]
        b = cp["1"]
        idx_a, _, ax, ay = int(a[0]), int(a[1]), float(a[2]), float(a[3])
        idx_b, _, bx, by = int(b[0]), int(b[1]), float(b[2]), float(b[3])
        key = (min(idx_a, idx_b), max(idx_a, idx_b), round(ax), round(ay), round(bx), round(by))
        if key in seen:
            continue
        seen.add(key)
        serial_a = ptgui_index_to_serial(calib, idx_a)
        serial_b = ptgui_index_to_serial(calib, idx_b)
        pa = project_camera_pixel_to_pano(serial_a, calib, ax, ay, width, height)
        pb = project_camera_pixel_to_pano(serial_b, calib, bx, by, width, height)
        if pa is None or pb is None:
            errors.append({"cp": cp, "error": "forward projection failed"})
            continue
        dist = math.hypot(pa[0] - pb[0], pa[1] - pb[1])
        errors.append({
            "serial_a": serial_a,
            "serial_b": serial_b,
            "pano_a": pa,
            "pano_b": pb,
            "dist_px": dist,
        })

    dists = [e["dist_px"] for e in errors if "dist_px" in e]
    if not dists:
        print("No control point pairs to validate")
        return True
    mean_e = sum(dists) / len(dists)
    max_e = max(dists)
    print(f"Control points: n={len(dists)} mean={mean_e:.2f}px max={max_e:.2f}px")
    for e in errors:
        if "dist_px" in e and e["dist_px"] > mean_threshold:
            print(f"  {e['serial_a']} vs {e['serial_b']}: {e['dist_px']:.2f}px")
    ok = mean_e <= mean_threshold and max_e <= max_threshold
    if not ok:
        print(f"FAIL thresholds mean<={mean_threshold} max<={max_threshold}")
    else:
        print("PASS")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description="Validate stitch calibration")
    ap.add_argument("calib_dir", type=Path, help="stitch-calib directory with calib.json")
    ap.add_argument("--mean-threshold", type=float, default=25.0, help="Max mean CP error (px)")
    ap.add_argument("--max-threshold", type=float, default=50.0, help="Max single CP error (px)")
    ap.add_argument("--pts", type=Path, help="Optional .pts to re-parse control points")
    args = ap.parse_args()

    calib_path = args.calib_dir / "calib.json"
    if args.pts:
        calib = parse_pts(args.pts)
        data = json.loads(calib_path.read_text(encoding="utf-8")) if calib_path.exists() else {}
        calib.output_width = int(data.get("output_width", 0))
        calib.output_height = int(data.get("output_height", 0))
        for serial, cam_d in data.get("cameras", {}).items():
            if serial in calib.cameras:
                if "yaw_offset_deg" in cam_d:
                    calib.cameras[serial].yaw_offset_deg = float(cam_d["yaw_offset_deg"])
                if "pitch_offset_deg" in cam_d:
                    calib.cameras[serial].pitch_offset_deg = float(cam_d["pitch_offset_deg"])
    else:
        calib = calib_from_dict(json.loads(calib_path.read_text(encoding="utf-8")))

    w = calib.output_width
    h = calib.output_height
    if w <= 0 or h <= 0:
        from panotools_math import compute_optimum_size
        w, h = compute_optimum_size(calib)

    # Ensure LUT exists (sanity)
    bake_warp_maps(calib, w, h)
    ok = validate_control_points(calib, w, h, args.mean_threshold, args.max_threshold)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
