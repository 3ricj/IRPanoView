#!/usr/bin/env python3
"""Forward-map control points to tune panotools math."""

from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from parse_ptgui_pts import parse_pts


def set_rotation_pt(yaw_deg: float, pitch_deg: float, roll_deg: float) -> np.ndarray:
    y = math.radians(yaw_deg)
    p = math.radians(pitch_deg)
    r = math.radians(roll_deg)
    cosr, sinr = math.cos(r), math.sin(r)
    cosp, sinp = math.cos(p), math.sin(-p)
    cosy, siny = math.cos(y), math.sin(-y)
    rollm = np.array([[cosr, -sinr, 0.0], [sinr, cosr, 0.0], [0.0, 0.0, 1.0]])
    pitchm = np.array([[cosp, 0.0, sinp], [0.0, 1.0, 0.0], [-sinp, 0.0, cosp]])
    yawm = np.array([[cosy, -siny, 0.0], [siny, cosy, 0.0], [0.0, 0.0, 1.0]])
    return yawm @ pitchm @ rollm


def undistort_px(px, py, w, h, lens, diag):
    cx = px - (w - 1) * 0.5
    cy = py - (h - 1) * 0.5
    cx -= lens.shift_long * diag
    cy -= lens.shift_short * diag
    scale = min(w, h) / 2.0
    rs = math.hypot(cx, cy) / max(scale, 1e-9)
    if rs < 1e-12:
        return 0.0, 0.0
    phi = math.atan2(cy, cx)
    rd = rs
    for _ in range(12):
        d = 1.0 - (lens.a + lens.b + lens.c)
        f = (lens.a * rd**3 + lens.b * rd**2 + lens.c * rd + d) * rd - rs
        df = lens.a * 4 * rd**3 + lens.b * 3 * rd**2 + lens.c * 2 * rd + d
        if abs(df) < 1e-12:
            break
        rd -= f / df
    xd = rd * scale * math.cos(phi)
    yd = rd * scale * math.sin(phi)
    return xd, yd


def cam_px_to_world(px, py, cam, lens, f_scale: float) -> np.ndarray | None:
    w, h = cam.width, cam.height
    diag = math.hypot(w, h)
    xd, yd = undistort_px(px, py, w, h, lens, diag)
    f_px = lens.focal_mm / lens.sensor_diagonal_mm * diag * f_scale
    v = np.array([xd, yd, f_px], dtype=np.float64)
    n = np.linalg.norm(v)
    if n < 1e-12:
        return None
    v /= n
    R = set_rotation_pt(cam.yaw_deg, cam.pitch_deg, cam.roll_deg)
    world = R @ v
    return world / np.linalg.norm(world)


def world_to_cyl_pano(world, w, h, hfov_deg, vfov_deg, v_mode: str):
    lat = math.asin(max(min(float(world[1]), 1.0), -1.0))
    lon = math.atan2(float(world[0]), float(world[2]))
    hfov = math.radians(hfov_deg)
    vfov = math.radians(vfov_deg)
    px = (lon / hfov + 0.5) * (w - 1)
    if v_mode == "linear":
        py = (0.5 - lat / vfov) * (h - 1)
    else:
        tan_half = math.tan(vfov * 0.5)
        y_norm = math.tan(lat) / max(tan_half, 1e-9)
        py = (y_norm * 0.5 + 0.5) * (h - 1)
    return px, py


def cam_px_to_pano(px, py, cam, calib, w, h, f_scale, v_mode):
    world = cam_px_to_world(px, py, cam, calib.lens, f_scale)
    if world is None:
        return None
    return world_to_cyl_pano(world, w, h, calib.pano.hfov_deg, calib.pano.vfov_deg, v_mode)


def cp_error(calib, w, h, f_scale, v_mode):
    serial_by_idx = {cam.ptgui_index: s for s, cam in calib.cameras.items()}
    dists = []
    for cp in calib.control_points:
        if cp.get("t") != 0:
            continue
        a, b = cp["0"], cp["1"]
        ia, _, ax, ay = int(a[0]), int(a[1]), float(a[2]), float(a[3])
        ib, _, bx, by = int(b[0]), int(b[1]), float(b[2]), float(b[3])
        pa = cam_px_to_pano(ax, ay, calib.cameras[serial_by_idx[ia]], calib, w, h, f_scale, v_mode)
        pb = cam_px_to_pano(bx, by, calib.cameras[serial_by_idx[ib]], calib, w, h, f_scale, v_mode)
        if pa is None or pb is None:
            continue
        dists.append(math.hypot(pa[0] - pb[0], pa[1] - pb[1]))
    return (sum(dists) / len(dists), max(dists)) if dists else (999.0, 999.0)


def main():
    calib = parse_pts(Path(__file__).resolve().parents[2] / "eq-samples" / "EA6462986 Panorama.pts")
    w, h = 972, 166
    best = (999.0, "")
    for v_mode in ("atan", "linear"):
        for f_scale in [x * 0.25 for x in range(2, 20)]:
            mean, mx = cp_error(calib, w, h, f_scale, v_mode)
            tag = f"v={v_mode} f={f_scale:.2f}"
            if mean < best[0]:
                best = (mean, tag)
            if f_scale in (1.0, 2.0, 3.0, 4.0):
                print(f"{tag}: mean={mean:.2f} max={mx:.2f}")
    print("BEST", best)


if __name__ == "__main__":
    main()
