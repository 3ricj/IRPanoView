"""Fit small per-camera yaw corrections against PTGUI control points."""

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


def fit_yaw_offsets(calib, width: int, height: int) -> dict[str, float]:
    """Return per-serial yaw corrections that minimize CP reprojection error."""
    lens = calib.lens
    pano = calib.pano
    serial_by_idx = {cam.ptgui_index: s for s, cam in calib.cameras.items()}
    serials = sorted(calib.cameras.keys(), key=lambda s: calib.cameras[s].ptgui_index)

    def cp_rms(yaw_offs: np.ndarray) -> float:
        off_map = {serials[i]: float(yaw_offs[i]) for i in range(len(serials))}

        def cam_px_to_pano(px, py, cam):
            cx = px - (cam.width - 1) / 2.0
            cy = py - (cam.height - 1) / 2.0
            cx, cy = remove_lens_shift(cx, cy, lens, cam.width, cam.height)
            cx, cy = undistort_coords(cx, cy, lens, cam.width, cam.height)
            f = focal_pixels(lens, cam.width, cam.height)
            v = np.array([cx, cy, f], float)
            v /= np.linalg.norm(v)
            r = rotation_matrix_ypr(
                cam.yaw_deg + off_map[cam.serial],
                cam.pitch_deg,
                cam.roll_deg,
            )
            world = r @ v
            return sphere_to_pano_pixel(world / np.linalg.norm(world), width, height, pano)

        ds = []
        for cp in calib.control_points:
            if cp.get("t", 0) != 0:
                continue
            a, b = cp["0"], cp["1"]
            ia, _, ax, ay = int(a[0]), int(a[1]), float(a[2]), float(a[3])
            ib, _, bx, by = int(b[0]), int(b[1]), float(b[2]), float(b[3])
            pa = cam_px_to_pano(ax, ay, calib.cameras[serial_by_idx[ia]])
            pb = cam_px_to_pano(bx, by, calib.cameras[serial_by_idx[ib]])
            ds.append((pa[0] - pb[0]) ** 2 + (pa[1] - pb[1]) ** 2)
        return math.sqrt(sum(ds) / len(ds))

    x0 = np.zeros(len(serials))
    res = optimize.minimize(
        cp_rms,
        x0,
        method="Nelder-Mead",
        options={"maxiter": 8000, "xatol": 1e-4, "fatol": 1e-4},
    )
    return {serials[i]: float(res.x[i]) for i in range(len(serials))}


def cp_rms(calib, w, h, yaw_offs):
    lens = calib.lens
    pano = calib.pano
    serial_by_idx = {cam.ptgui_index: s for s, cam in calib.cameras.items()}
    serials = sorted(calib.cameras.keys(), key=lambda s: calib.cameras[s].ptgui_index)
    off_map = {serials[i]: float(yaw_offs[i]) for i in range(len(serials))}

    def cam_px_to_pano(px, py, cam):
        cx = px - (cam.width - 1) / 2.0
        cy = py - (cam.height - 1) / 2.0
        cx, cy = remove_lens_shift(cx, cy, lens, cam.width, cam.height)
        cx, cy = undistort_coords(cx, cy, lens, cam.width, cam.height)
        f = focal_pixels(lens, cam.width, cam.height)
        v = np.array([cx, cy, f], float)
        v /= np.linalg.norm(v)
        r = rotation_matrix_ypr(
            cam.yaw_deg + off_map[cam.serial],
            cam.pitch_deg,
            cam.roll_deg,
        )
        world = r @ v
        return sphere_to_pano_pixel(world / np.linalg.norm(world), w, h, pano)

    ds = []
    for cp in calib.control_points:
        if cp.get("t", 0) != 0:
            continue
        a, b = cp["0"], cp["1"]
        ia, _, ax, ay = int(a[0]), int(a[1]), float(a[2]), float(a[3])
        ib, _, bx, by = int(b[0]), int(b[1]), float(b[2]), float(b[3])
        pa = cam_px_to_pano(ax, ay, calib.cameras[serial_by_idx[ia]])
        pb = cam_px_to_pano(bx, by, calib.cameras[serial_by_idx[ib]])
        ds.append((pa[0] - pb[0]) ** 2 + (pa[1] - pb[1]) ** 2)
    return math.sqrt(sum(ds) / len(ds))


def main() -> int:
    calib = parse_pts(Path(__file__).resolve().parents[2] / "eq-samples" / "EA6462986 Panorama.pts")
    w, h = 972, 166
    n = len(calib.cameras)
    x0 = np.zeros(n)
    offsets = fit_yaw_offsets(calib, w, h)
    print(f"baseline rms={cp_rms(calib, w, h, x0):.3f}px")
    off_arr = np.array([offsets[s] for s in sorted(calib.cameras.keys(), key=lambda s: calib.cameras[s].ptgui_index)])
    print(f"optimized rms={cp_rms(calib, w, h, off_arr):.3f}px")
    for s, off in offsets.items():
        print(f"  {s} yaw_off={off:+.4f} deg")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
