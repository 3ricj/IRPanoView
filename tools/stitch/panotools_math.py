"""Panotools / PTGUI lens and projection math for IRPanoView stitch baking."""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np

DEG2RAD = math.pi / 180.0
RAD2DEG = 180.0 / math.pi
FIXED_POINT = 256  # subpixel coords stored as int * FIXED_POINT


@dataclass
class LensParams:
    projection: str = "rectilinear"
    focal_mm: float = 3.0
    sensor_diagonal_mm: float = 3.84
    a: float = 0.0
    b: float = 0.0
    c: float = 0.0
    shift_long: float = 0.0
    shift_short: float = 0.0

    def d(self) -> float:
        return 1.0 - (self.a + self.b + self.c)


@dataclass
class CameraPose:
    serial: str
    width: int
    height: int
    yaw_deg: float
    pitch_deg: float
    roll_deg: float
    ptgui_index: int = 0
    yaw_offset_deg: float = 0.0
    pitch_offset_deg: float = 0.0


@dataclass
class PanoParams:
    projection: str = "cylindrical"
    hfov_deg: float = 360.0
    vfov_deg: float = 90.0
    crop: Tuple[float, float, float, float] = (0.0, 0.0, 1.0, 1.0)
    size_fraction: float = 1.0


@dataclass
class StitchCalib:
    version: int = 1
    source_pts: str = ""
    lens: LensParams = field(default_factory=LensParams)
    pano: PanoParams = field(default_factory=PanoParams)
    cameras: Dict[str, CameraPose] = field(default_factory=dict)
    control_points: List[dict] = field(default_factory=list)
    output_width: int = 0
    output_height: int = 0


def rotation_matrix_ypr(yaw_deg: float, pitch_deg: float, roll_deg: float) -> np.ndarray:
    """PTGUI image orientation: Rz(roll) @ Rx(pitch) @ Ry(yaw), camera looks down +Z."""
    y = yaw_deg * DEG2RAD
    p = pitch_deg * DEG2RAD
    r = roll_deg * DEG2RAD
    cy, sy = math.cos(y), math.sin(y)
    cp, sp = math.cos(p), math.sin(p)
    cr, sr = math.cos(r), math.sin(r)
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], dtype=np.float64)
    rx = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]], dtype=np.float64)
    rz = np.array([[cr, -sr, 0], [sr, cr, 0], [0, 0, 1]], dtype=np.float64)
    return rz @ rx @ ry


def focal_pixels(lens: LensParams, width: int, height: int) -> float:
    diag_px = math.hypot(width, height)
    return lens.focal_mm / lens.sensor_diagonal_mm * diag_px


def norm_radius_r0(width: int, height: int) -> float:
    return min(width, height) / 2.0


def apply_distortion(xd: float, yd: float, lens: LensParams, width: int, height: int) -> Tuple[float, float]:
    """Map ideal rectilinear coords (from center) to distorted source coords."""
    scale = norm_radius_r0(width, height)
    if scale <= 0:
        return xd, yd
    xd_n = xd / scale
    yd_n = yd / scale
    r_dest = math.hypot(xd_n, yd_n)
    if r_dest < 1e-12:
        return xd, yd
    phi = math.atan2(yd_n, xd_n)
    d = lens.d()
    r_src = (lens.a * r_dest**3 + lens.b * r_dest**2 + lens.c * r_dest + d) * r_dest
    return r_src * scale * math.cos(phi), r_src * scale * math.sin(phi)


def undistort_coords(xd: float, yd: float, lens: LensParams, width: int, height: int) -> Tuple[float, float]:
    """Invert apply_distortion on rectilinear coords."""
    scale = norm_radius_r0(width, height)
    rs = math.hypot(xd, yd) / max(scale, 1e-9)
    if rs < 1e-12:
        return xd, yd
    phi = math.atan2(yd, xd)
    rd = rs
    for _ in range(12):
        d = lens.d()
        f = (lens.a * rd**3 + lens.b * rd**2 + lens.c * rd + d) * rd - rs
        df = lens.a * 4 * rd**3 + lens.b * 3 * rd**2 + lens.c * 2 * rd + d
        if abs(df) < 1e-12:
            break
        rd -= f / df
    return rd * scale * math.cos(phi), rd * scale * math.sin(phi)


def apply_lens_shift(x: float, y: float, lens: LensParams, width: int, height: int) -> Tuple[float, float]:
    diag = math.hypot(width, height)
    if width >= height:
        return x + lens.shift_long * diag, y + lens.shift_short * diag
    return x + lens.shift_short * diag, y + lens.shift_long * diag


def remove_lens_shift(x: float, y: float, lens: LensParams, width: int, height: int) -> Tuple[float, float]:
    diag = math.hypot(width, height)
    if width >= height:
        return x - lens.shift_long * diag, y - lens.shift_short * diag
    return x - lens.shift_short * diag, y - lens.shift_long * diag


def camera_ray_to_pixel(v: np.ndarray, lens: LensParams, width: int, height: int) -> Optional[Tuple[float, float]]:
    """Unit direction in camera space (+Z forward) -> source pixel."""
    x, y, z = float(v[0]), float(v[1]), float(v[2])
    if z <= 1e-9:
        return None
    if lens.projection != "rectilinear":
        return None
    f_px = focal_pixels(lens, width, height)
    xd = f_px * (x / z)
    yd = f_px * (y / z)
    xs, ys = apply_distortion(xd, yd, lens, width, height)
    xs, ys = apply_lens_shift(xs, ys, lens, width, height)
    px = xs + (width - 1) / 2.0
    py = ys + (height - 1) / 2.0
    if px < -0.5 or py < -0.5 or px > width - 0.5 or py > height - 1.5:
        return None
    return px, py


def pixel_to_camera_ray(px: float, py: float, lens: LensParams, width: int, height: int) -> Optional[np.ndarray]:
    """Source pixel -> unit direction in camera space (+Z forward)."""
    if lens.projection != "rectilinear":
        return None
    cx = px - (width - 1) / 2.0
    cy = py - (height - 1) / 2.0
    cx, cy = remove_lens_shift(cx, cy, lens, width, height)
    cx, cy = undistort_coords(cx, cy, lens, width, height)
    f_px = focal_pixels(lens, width, height)
    v = np.array([cx, cy, f_px], dtype=np.float64)
    n = np.linalg.norm(v)
    if n < 1e-12:
        return None
    return v / n


def pano_pixel_to_sphere(
    px: float, py: float, width: int, height: int, pano: PanoParams
) -> np.ndarray:
    """Cylindrical pano pixel -> unit direction (Y up). Vertical axis uses tan(lat)."""
    if pano.projection != "cylindrical":
        raise ValueError(f"unsupported pano projection: {pano.projection}")
    hfov = pano.hfov_deg * DEG2RAD
    vfov = pano.vfov_deg * DEG2RAD
    lon = (px / max(width - 1, 1) - 0.5) * hfov
    tan_half_v = math.tan(vfov * 0.5)
    # PTGUI/libpano cylindrical: +Y pano row = downward on output image.
    y_norm = (py / max(height - 1, 1) - 0.5) * 2.0
    lat = math.atan(y_norm * tan_half_v)
    clat = math.cos(lat)
    return np.array([clat * math.sin(lon), math.sin(lat), clat * math.cos(lon)], dtype=np.float64)


def sphere_to_pano_pixel(
    world: np.ndarray, width: int, height: int, pano: PanoParams
) -> Tuple[float, float]:
    """Unit direction -> cylindrical pano pixel."""
    x, y, z = float(world[0]), float(world[1]), float(world[2])
    lon = math.atan2(x, z)
    lat = math.asin(max(min(y, 1.0), -1.0))
    hfov = pano.hfov_deg * DEG2RAD
    vfov = pano.vfov_deg * DEG2RAD
    tan_half_v = math.tan(vfov * 0.5)
    out_x = (lon / hfov + 0.5) * (width - 1)
    y_norm = math.tan(lat) / max(tan_half_v, 1e-9)
    out_y = (0.5 + y_norm * 0.5) * (height - 1)
    return out_x, out_y


def compute_optimum_size(calib: StitchCalib) -> Tuple[int, int]:
    if not calib.cameras:
        return 1024, 192
    ppd_max = 0.0
    for cam in calib.cameras.values():
        f_px = focal_pixels(calib.lens, cam.width, cam.height)
        hfov_cam = 2.0 * math.degrees(math.atan(cam.width / (2.0 * max(f_px, 1e-6))))
        if hfov_cam > 1e-6:
            ppd_max = max(ppd_max, cam.width / hfov_cam)
    if ppd_max <= 0:
        ppd_max = 4.0
    w = int(round(calib.pano.hfov_deg * ppd_max * calib.pano.size_fraction))
    h = int(round(calib.pano.vfov_deg * ppd_max * calib.pano.size_fraction))
    w = max(w, 256)
    h = max(h, 64)
    cx0, cy0, cx1, cy1 = calib.pano.crop
    w = max(int(round(w * (cx1 - cx0))), 64)
    h = max(int(round(h * (cy1 - cy0))), 32)
    w = (w + 1) & ~1
    h = (h + 1) & ~1
    return w, h


def bake_warp_maps(
    calib: StitchCalib,
    width: Optional[int] = None,
    height: Optional[int] = None,
    feather: str = "cosine",
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, List[str]]:
    serials = sorted(calib.cameras.keys())
    num_cams = len(serials)
    if width is None or height is None:
        width, height = compute_optimum_size(calib)
    calib.output_width = width
    calib.output_height = height

    src_x = np.full((num_cams, height, width), -1.0, dtype=np.float32)
    src_y = np.full((num_cams, height, width), -1.0, dtype=np.float32)
    raw_w = np.zeros((num_cams, height, width), dtype=np.float32)

    rotations = []
    for serial in serials:
        cam = calib.cameras[serial]
        rotations.append(
            rotation_matrix_ypr(
                cam.yaw_deg + cam.yaw_offset_deg,
                cam.pitch_deg + cam.pitch_offset_deg,
                cam.roll_deg,
            )
        )

    for row in range(height):
        for col in range(width):
            world = pano_pixel_to_sphere(float(col), float(row), width, height, calib.pano)
            for ci, serial in enumerate(serials):
                cam = calib.cameras[serial]
                r = rotations[ci]
                v_cam = r.T @ world
                n = np.linalg.norm(v_cam)
                if n < 1e-12:
                    continue
                hit = camera_ray_to_pixel(v_cam / n, calib.lens, cam.width, cam.height)
                if hit is None:
                    continue
                sx, sy = hit
                src_x[ci, row, col] = sx
                src_y[ci, row, col] = sy
                raw_w[ci, row, col] = 1.0

    weights = np.zeros_like(raw_w)
    for row in range(height):
        for col in range(width):
            active = [ci for ci in range(num_cams) if raw_w[ci, row, col] > 0]
            if not active:
                continue
            if len(active) == 1:
                weights[active[0], row, col] = 1.0
                continue
            world = pano_pixel_to_sphere(float(col), float(row), width, height, calib.pano)
            scores = []
            for ci in active:
                r = rotations[ci]
                v_cam = r.T @ world
                n = np.linalg.norm(v_cam)
                if n < 1e-12:
                    scores.append(0.0)
                    continue
                v_cam = v_cam / n
                # +Z forward; cos^2 falloff keeps both overlap cameras contributing.
                cos_a = max(min(float(v_cam[2]), 1.0), 0.0)
                if feather == "cosine":
                    scores.append(cos_a * cos_a)
                else:
                    scores.append(cos_a)
            ssum = sum(scores)
            if ssum <= 0:
                w0 = 1.0 / len(active)
                for ci in active:
                    weights[ci, row, col] = w0
            else:
                for i, ci in enumerate(active):
                    weights[ci, row, col] = scores[i] / ssum

    return src_x, src_y, weights, serials


def project_camera_pixel_to_pano(
    serial: str,
    calib: StitchCalib,
    px: float,
    py: float,
    width: int,
    height: int,
) -> Optional[Tuple[float, float]]:
    cam = calib.cameras[serial]
    serials = sorted(calib.cameras.keys())
    if serial not in serials:
        return None
    v_cam = pixel_to_camera_ray(px, py, calib.lens, cam.width, cam.height)
    if v_cam is None:
        return None
    r = rotation_matrix_ypr(
        cam.yaw_deg + cam.yaw_offset_deg,
        cam.pitch_deg + cam.pitch_offset_deg,
        cam.roll_deg,
    )
    world = r @ v_cam
    n = np.linalg.norm(world)
    if n < 1e-12:
        return None
    return sphere_to_pano_pixel(world / n, width, height, calib.pano)
