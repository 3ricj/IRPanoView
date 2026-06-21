#!/usr/bin/env python3
"""Export PTGUI calib to libpano PTO and sweep scale/rotation variants."""

from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from parse_ptgui_pts import parse_pts
from panotools_math import (
    focal_pixels,
    remove_lens_shift,
    rotation_matrix_ypr,
    undistort_coords,
)


def lens_hfov_deg(lens, width, height):
    f_px = focal_pixels(lens, width, height)
    return 2.0 * math.degrees(math.atan(width / (2.0 * max(f_px, 1e-9))))


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


def export_pto(calib, out_path: Path, w: int, h: int) -> None:
    lens = calib.lens
    lines = [
        "# exported from IRPanoView",
        f"p f1 w{w} h{h} v{calib.pano.hfov_deg:.6f} n\"JPEG\"",
        "m g1 i0",
        "",
    ]
    serials = sorted(calib.cameras.keys(), key=lambda s: calib.cameras[s].ptgui_index)
    for serial in serials:
        cam = calib.cameras[serial]
        v = lens_hfov_deg(lens, cam.width, cam.height)
        lines.append(
            f"i w{cam.width} h{cam.height} f0 v{v:.6f} "
            f"y{cam.yaw_deg:.6f} p{cam.pitch_deg:.6f} r{cam.roll_deg:.6f} "
            f"a{lens.a:.8f} b{lens.b:.8f} c{lens.c:.8f} "
            f"d{lens.d():.8f} "
            f"E{lens.shift_long:.8f} N{lens.shift_short:.8f} "
            f"n\"{serial}.jpg\""
        )
    lines.append("")
    for cp in calib.control_points:
        if cp.get("t", 0) != 0:
            continue
        a, b = cp["0"], cp["1"]
        lines.append(
            f"c n0 N0 x {a[2]:.2f} {a[3]:.2f} X {b[2]:.2f} {b[3]:.2f} "
            f"i {a[0]} I {b[0]}"
        )
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def cp_error(calib, w, h, rot_fn, z_sign, f_scale, v_mode, use_eq_pipeline: bool):
    lens = calib.lens
    serial_by_idx = {cam.ptgui_index: s for s, cam in calib.cameras.items()}
    e_cache: dict[tuple[int, int], float] = {}

    def e_factor(cw, ch):
        key = (cw, ch)
        if key not in e_cache:
            hh = lens_hfov_deg(lens, cw, ch)
            e_cache[key] = (cw / 2.0) / max(math.tan(math.radians(hh / 2.0)), 1e-9)
        return e_cache[key]

    def pano_from_world(x, y, z):
        lon = math.atan2(x, z)
        lat = math.asin(max(-1.0, min(1.0, y)))
        hfov = calib.pano.hfov_deg * DEG2RAD
        vfov = calib.pano.vfov_deg * DEG2RAD
        px = (lon / hfov + 0.5) * (w - 1)
        if v_mode == "atan":
            tan_half = math.tan(vfov * 0.5)
            py = (0.5 + math.tan(lat) / max(tan_half, 1e-9) * 0.5) * (h - 1)
        else:
            py = (0.5 - lat / vfov) * (h - 1)
        return px, py

    def cam_to_world(px, py, cam):
        cw, ch = cam.width, cam.height
        cx = px - (cw - 1) / 2.0
        cy = py - (ch - 1) / 2.0
        cx, cy = remove_lens_shift(cx, cy, lens, cw, ch)
        if use_eq_pipeline:
            e = e_factor(cw, ch) * f_scale
            cx, cy = undistort_rect_to_eq(cx, cy, e, lens, cw, ch)
            rd = math.hypot(cx, cy)
            if rd < 1e-15:
                return np.array([0.0, 0.0, float(z_sign)], float)
            ang = rd / e
            phi = math.atan2(cy, cx)
            st = math.sin(ang)
            return np.array([st * math.cos(phi), st * math.sin(phi), z_sign * -math.cos(ang)], float)
        cx, cy = undistort_coords(cx, cy, lens, cw, ch)
        f = focal_pixels(lens, cw, ch) * f_scale
        v = np.array([cx, cy, z_sign * f], float)
        return v / np.linalg.norm(v)

    dists = []
    for cp in calib.control_points:
        if cp.get("t", 0) != 0:
            continue
        a, b = cp["0"], cp["1"]
        ia, _, ax, ay = int(a[0]), int(a[1]), float(a[2]), float(a[3])
        ib, _, bx, by = int(b[0]), int(b[1]), float(b[2]), float(b[3])
        ca = calib.cameras[serial_by_idx[ia]]
        cb = calib.cameras[serial_by_idx[ib]]
        va = cam_to_world(ax, ay, ca)
        vb = cam_to_world(bx, by, cb)
        Ra = rot_fn(ca.yaw_deg, ca.pitch_deg, ca.roll_deg)
        Rb = rot_fn(cb.yaw_deg, cb.pitch_deg, cb.roll_deg)
        wa = Ra @ va
        wb = Rb @ vb
        pa = pano_from_world(*(wa / np.linalg.norm(wa)))
        pb = pano_from_world(*(wb / np.linalg.norm(wb)))
        dists.append(math.hypot(pa[0] - pb[0], pa[1] - pb[1]))
    return (sum(dists) / len(dists), max(dists)) if dists else (999.0, 999.0)


def undistort_rect_to_eq(h, v, e, lens, width, height):
    """Rectilinear -> equal-angle, undistort, return equal-angle coords."""
    rd = math.hypot(h, v)
    if rd < 1e-15:
        return 0.0, 0.0
    ang = math.atan2(rd, e)
    rt = e * ang
    h *= rt / rd
    v *= rt / rd
    r0 = min(width, height) / 2.0
    rd = math.hypot(h, v)
    if rd < 1e-15:
        return h, v
    x = rd / r0
    scale = lens.d() + lens.a * x + lens.b * x * x + lens.c * x * x * x
    rd_obs = rd
    rd_ideal = rd_obs / max(scale, 1e-9)
    for _ in range(10):
        x = rd_ideal / r0
        scale = lens.d() + lens.a * x + lens.b * x * x + lens.c * x * x * x
        rd_ideal = rd_obs / max(scale, 1e-9)
    return h * (rd_ideal / rd_obs), v * (rd_ideal / rd_obs)


DEG2RAD = math.pi / 180.0


def main() -> int:
    calib = parse_pts(Path(__file__).resolve().parents[2] / "eq-samples" / "EA6462986 Panorama.pts")
    w, h = 972, 166
    pto = Path(__file__).resolve().parents[2] / "stitch-calib" / "export.pto"
    export_pto(calib, pto, w, h)
    print(f"Wrote {pto}")

    best = (999.0, "")
    for rot_name, rot_fn in [("old", rotation_matrix_ypr), ("pt", set_rotation_pt)]:
        for zs in (1, -1):
            for vm in ("atan", "linear"):
                for eq in (False, True):
                    for fs in [x * 0.25 for x in range(2, 33)]:
                        m, mx = cp_error(calib, w, h, rot_fn, zs, fs, vm, eq)
                        tag = f"{rot_name} z={zs} v={vm} eq={eq} f={fs:.2f}"
                        if m < best[0]:
                            best = (m, tag, mx)
    print(f"BEST mean={best[0]:.3f}px max={best[2]:.2f}  {best[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
