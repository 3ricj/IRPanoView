"""Fit per-camera yaw/pitch corrections against PTGUI control points."""

from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
from scipy import optimize

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from parse_ptgui_pts import parse_pts
from panotools_math import (
    focal_pixels,
    remove_lens_shift,
    rotation_matrix_ypr,
    sphere_to_pano_pixel,
    undistort_coords,
)


def fit_pose_offsets(calib, width: int, height: int) -> tuple[dict[str, float], dict[str, float]]:
    lens = calib.lens
    pano = calib.pano
    serial_by_idx = {cam.ptgui_index: s for s, cam in calib.cameras.items()}
    serials = sorted(calib.cameras.keys(), key=lambda s: calib.cameras[s].ptgui_index)
    n = len(serials)

    def cam_px_to_pano(px, py, cam, yaw_off, pitch_off):
        cx = px - (cam.width - 1) / 2.0
        cy = py - (cam.height - 1) / 2.0
        cx, cy = remove_lens_shift(cx, cy, lens, cam.width, cam.height)
        cx, cy = undistort_coords(cx, cy, lens, cam.width, cam.height)
        f = focal_pixels(lens, cam.width, cam.height)
        v = np.array([cx, cy, f], float)
        v /= np.linalg.norm(v)
        r = rotation_matrix_ypr(
            cam.yaw_deg + yaw_off,
            cam.pitch_deg + pitch_off,
            cam.roll_deg,
        )
        world = r @ v
        return sphere_to_pano_pixel(world / np.linalg.norm(world), width, height, pano)

    def cp_rms(params: np.ndarray) -> float:
        ds = []
        for cp in calib.control_points:
            if cp.get("t", 0) != 0:
                continue
            a, b = cp["0"], cp["1"]
            ia, _, ax, ay = int(a[0]), int(a[1]), float(a[2]), float(a[3])
            ib, _, bx, by = int(b[0]), int(b[1]), float(b[2]), float(b[3])
            ca = calib.cameras[serial_by_idx[ia]]
            cb = calib.cameras[serial_by_idx[ib]]
            pa = cam_px_to_pano(ax, ay, ca, params[ia], params[n + ia])
            pb = cam_px_to_pano(bx, by, cb, params[ib], params[n + ib])
            ds.append((pa[0] - pb[0]) ** 2 + (pa[1] - pb[1]) ** 2)
        return math.sqrt(sum(ds) / len(ds))

    x0 = np.zeros(2 * n)
    res = optimize.minimize(
        cp_rms,
        x0,
        method="Nelder-Mead",
        options={"maxiter": 12000, "xatol": 1e-4, "fatol": 1e-4},
    )
    yaw = {serials[i]: float(res.x[i]) for i in range(n)}
    pitch = {serials[i]: float(res.x[n + i]) for i in range(n)}
    return yaw, pitch


def apply_pose_offsets(calib, width: int, height: int) -> float:
    yaw, pitch = fit_pose_offsets(calib, width, height)
    for serial in calib.cameras:
        calib.cameras[serial].yaw_offset_deg = yaw[serial]
        calib.cameras[serial].pitch_offset_deg = pitch[serial]
    rms = _report_rms(calib, width, height)
    return rms


def _report_rms(calib, width: int, height: int) -> float:
    from panotools_math import project_camera_pixel_to_pano

    serial_by_idx = {cam.ptgui_index: s for s, cam in calib.cameras.items()}
    ds = []
    for cp in calib.control_points:
        if cp.get("t", 0) != 0:
            continue
        a, b = cp["0"], cp["1"]
        ia, _, ax, ay = int(a[0]), int(a[1]), float(a[2]), float(a[3])
        ib, _, bx, by = int(b[0]), int(b[1]), float(b[2]), float(b[3])
        pa = project_camera_pixel_to_pano(serial_by_idx[ia], calib, ax, ay, width, height)
        pb = project_camera_pixel_to_pano(serial_by_idx[ib], calib, bx, by, width, height)
        if pa and pb:
            ds.append(math.hypot(pa[0] - pb[0], pa[1] - pb[1]))
    return sum(ds) / len(ds) if ds else 999.0


def main() -> int:
    calib = parse_pts(Path(__file__).resolve().parents[2] / "eq-samples" / "EA6462986 Panorama.pts")
    w, h = 972, 166
    rms = apply_pose_offsets(calib, w, h)
    print(f"CP mean after fit: {rms:.2f}px")
    for s in sorted(calib.cameras.keys(), key=lambda x: calib.cameras[x].ptgui_index):
        c = calib.cameras[s]
        print(f"  {s} yaw={c.yaw_offset_deg:+.3f} pitch={c.pitch_offset_deg:+.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
