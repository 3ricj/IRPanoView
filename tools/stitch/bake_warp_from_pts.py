#!/usr/bin/env python3
"""
Bake warp_lut.bin from PTGUI .pts JSON (forward projection).

pano ray -> R.T @ ray -> rectilinear + PTGUI distortion -> per-camera src uv;
hard winner by ray_cam[2] for stitch weights.

Validate against PTGUI individual layer exports before deploy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from bake_stitch_luts import reorder_by_slot, stitch_preview_jpeg, write_eq_seam_samples, write_seam_masks, write_warp_lut
from parse_ptgui_pts import calib_to_dict, parse_pts

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    Image = None  # type: ignore


@dataclass(frozen=True)
class Convention:
    rot_order: str = "YXZ"
    use_rt: bool = True
    pano_v_linear: bool = False
    pano_y_down: bool = False
    shift_mode: str = "ptgui_sides"  # ptgui_sides | panotools_diag
    distort_mode: str = "pixel_pano"  # pixel_pano | angular
    proj_y_flip: bool = False
    center_at_half: bool = False  # False -> (w-1)/2 like libpano
    yaw_sign: float = 1.0
    pitch_sign: float = 1.0
    roll_sign: float = 1.0
    shift_sign: float = 1.0


def rot_x(a: float) -> np.ndarray:
    ca, sa = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, ca, -sa], [0, sa, ca]], dtype=np.float64)


def rot_y(a: float) -> np.ndarray:
    ca, sa = math.cos(a), math.sin(a)
    return np.array([[ca, 0, sa], [0, 1, 0], [-sa, 0, ca]], dtype=np.float64)


def rot_z(a: float) -> np.ndarray:
    ca, sa = math.cos(a), math.sin(a)
    return np.array([[ca, -sa, 0], [sa, ca, 0], [0, 0, 1]], dtype=np.float64)


def camera_rotation(yaw_deg: float, pitch_deg: float, roll_deg: float, conv: Convention) -> np.ndarray:
    y = math.radians(yaw_deg * conv.yaw_sign)
    p = math.radians(pitch_deg * conv.pitch_sign)
    r = math.radians(roll_deg * conv.roll_sign)
    if conv.rot_order == "YXZ":
        return rot_y(y) @ rot_x(p) @ rot_z(r)
    if conv.rot_order == "ZXY":
        return rot_z(r) @ rot_x(p) @ rot_y(y)
    if conv.rot_order == "ZYX":
        return rot_z(r) @ rot_y(y) @ rot_x(p)
    raise ValueError(conv.rot_order)


def principal_point(
    shift_long: float,
    shift_short: float,
    src_w: int,
    src_h: int,
    conv: Convention,
) -> Tuple[float, float]:
    """PTGUI shift: longside/shortside are fractions (0.1273 = 12.73%)."""
    sl = shift_long * conv.shift_sign
    ss = shift_short * conv.shift_sign
    cx_base = src_w / 2.0 if conv.center_at_half else (src_w - 1) / 2.0
    cy_base = src_h / 2.0 if conv.center_at_half else (src_h - 1) / 2.0
    if conv.shift_mode == "ptgui_sides":
        if src_w >= src_h:
            return cx_base + sl * src_w, cy_base + ss * src_h
        return cx_base + ss * src_w, cy_base + sl * src_h
    if conv.shift_mode == "panotools_diag":
        return cx_base, cy_base
    raise ValueError(f"unknown shift_mode {conv.shift_mode}")


def distort_pixel_pano(xd: np.ndarray, yd: np.ndarray, a: float, b: float, c: float, r0: float) -> Tuple[np.ndarray, np.ndarray]:
    """Libpano / PTGUI polynomial in pixel space (offset from center)."""
    d = 1.0 - a - b - c
    xd_n = xd / r0
    yd_n = yd / r0
    r_dest = np.hypot(xd_n, yd_n)
    phi = np.arctan2(yd_n, xd_n)
    r_src = (a * r_dest**3 + b * r_dest**2 + c * r_dest + d) * r_dest
    xs = r_src * r0 * np.cos(phi)
    ys = r_src * r0 * np.sin(phi)
    small = r_dest < 1e-12
    xs = np.where(small, xd, xs)
    ys = np.where(small, yd, ys)
    return xs, ys


def apply_panotools_shift_on_offset(
    xd: np.ndarray,
    yd: np.ndarray,
    shift_long: float,
    shift_short: float,
    src_w: int,
    src_h: int,
    conv: Convention,
) -> Tuple[np.ndarray, np.ndarray]:
    sl = shift_long * conv.shift_sign
    ss = shift_short * conv.shift_sign
    diag = math.hypot(src_w, src_h)
    if src_w >= src_h:
        return xd + sl * diag, yd + ss * diag
    return xd + ss * diag, yd + sl * diag


def pano_rays(out_w: int, out_h: int, hfov: float, vfov: float, conv: Convention) -> np.ndarray:
    xs = np.arange(out_w, dtype=np.float64)
    ys = np.arange(out_h, dtype=np.float64)
    ox, oy = np.meshgrid(xs, ys)
    theta = (ox / max(out_w - 1, 1) - 0.5) * hfov
    if conv.pano_y_down:
        v = (0.5 - oy / max(out_h - 1, 1)) * vfov
    else:
        v = (oy / max(out_h - 1, 1) - 0.5) * vfov
    if conv.pano_v_linear:
        rays = np.stack([np.sin(theta), np.tan(v), np.cos(theta)], axis=-1)
    else:
        tan_half = math.tan(vfov * 0.5)
        y_norm = (0.5 - oy / max(out_h - 1, 1)) * 2.0 if conv.pano_y_down else (oy / max(out_h - 1, 1) - 0.5) * 2.0
        lat = np.arctan(y_norm * tan_half)
        clat = np.cos(lat)
        rays = np.stack([clat * np.sin(theta), np.sin(lat), clat * np.cos(theta)], axis=-1)
    n = np.linalg.norm(rays, axis=-1, keepdims=True)
    return rays / np.maximum(n, 1e-12)


def project_camera_grid(
    ray_cam: np.ndarray,
    f_px: float,
    cx: float,
    cy: float,
    shift_long: float,
    shift_short: float,
    a: float,
    b: float,
    c: float,
    src_w: int,
    src_h: int,
    conv: Convention,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    x, y, z = ray_cam[..., 0], ray_cam[..., 1], ray_cam[..., 2]
    valid = z > 1e-9
    xn = np.where(valid, x / z, 0.0)
    yn = np.where(valid, y / z, 0.0)

    xd = f_px * xn
    yd = f_px * yn

    if conv.distort_mode == "pixel_pano":
        r0 = min(src_w, src_h) / 2.0
        xd, yd = distort_pixel_pano(xd, yd, a, b, c, r0)
    elif conv.distort_mode == "angular":
        r = np.hypot(xn, yn)
        scale = 1.0 + c * r + b * r * r + a * r * r * r
        xd = f_px * xn * scale
        yd = f_px * yn * scale
    else:
        raise ValueError(conv.distort_mode)

    if conv.shift_mode == "panotools_diag":
        xd, yd = apply_panotools_shift_on_offset(xd, yd, shift_long, shift_short, src_w, src_h, conv)

    if conv.proj_y_flip:
        u = cx + xd
        v = cy - yd
    else:
        u = cx + xd
        v = cy + yd

    in_bounds = valid & (u >= 0.0) & (u < src_w - 1) & (v >= 0.0) & (v < src_h - 1)
    score = np.where(in_bounds, z, -1.0)
    u_out = np.where(in_bounds, u, -1.0).astype(np.float32)
    v_out = np.where(in_bounds, v, -1.0).astype(np.float32)
    return u_out, v_out, score


def load_pts_project(pts_path: Path) -> dict:
    with pts_path.open(encoding="utf-8-sig") as f:
        return json.load(f)["project"]


def bake_lut(
    project: dict,
    out_w: int,
    out_h: int,
    conv: Convention,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, List[str]]:
    pano = project["panoramaparams"]
    lens = project["globallenses"][0]["lens"]["params"]
    shift = project["globallenses"][0]["shift"]["params"]
    groups = project["imagegroups"]

    hfov = math.radians(float(pano["hfov"]))
    vfov = math.radians(float(pano["vfov"]))
    src_w, src_h = int(groups[0]["size"][0]), int(groups[0]["size"][1])
    diag_px = math.hypot(src_w, src_h)
    f_px = float(lens["focallength"]) / float(lens["sensordiagonal"]) * diag_px
    a, b, c = float(lens["a"]), float(lens["b"]), float(lens["c"])
    shift_long = float(shift["longside"])
    shift_short = float(shift["shortside"])
    cx, cy = principal_point(shift_long, shift_short, src_w, src_h, conv)

    rays_world = pano_rays(out_w, out_h, hfov, vfov, conv)
    num_cams = len(groups)
    src_x = np.full((num_cams, out_h, out_w), -1.0, dtype=np.float32)
    src_y = np.full((num_cams, out_h, out_w), -1.0, dtype=np.float32)
    scores = np.full((num_cams, out_h, out_w), -1.0, dtype=np.float32)
    serials: List[str] = []

    for ci, g in enumerate(groups):
        pos = g["position"]["params"]
        serials.append(Path(g["images"][0]["filename"]).stem)
        rmat = camera_rotation(float(pos["yaw"]), float(pos["pitch"]), float(pos["roll"]), conv)
        r_use = rmat.T if conv.use_rt else rmat
        ray_cam = rays_world @ r_use.T
        u, v, score = project_camera_grid(
            ray_cam, f_px, cx, cy, shift_long, shift_short, a, b, c, src_w, src_h, conv
        )
        src_x[ci] = u
        src_y[ci] = v
        scores[ci] = score

    winner = scores.argmax(axis=0)
    weights = np.zeros((num_cams, out_h, out_w), dtype=np.float32)
    for ci in range(num_cams):
        weights[ci] = (winner == ci).astype(np.float32)

    return src_x, src_y, weights, serials


def load_ptgui_layers(
    serials: List[str], layers_dir: Path, out_w: int, out_h: int, lum_thresh: int = 8
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Return (layer_rgb[num_cams,H,W,3], layer_mask[num_cams,H,W], blended[H,W,3])."""
    num_cams = len(serials)
    layer_rgb = np.zeros((num_cams, out_h, out_w, 3), dtype=np.float64)
    layer_mask = np.zeros((num_cams, out_h, out_w), dtype=bool)
    for ci in range(num_cams):
        arr = np.array(Image.open(layer_ref_path(layers_dir, ci)).convert("RGB"))
        if arr.shape[:2] != (out_h, out_w):
            raise ValueError(
                f"layer {ci} is {arr.shape[1]}x{arr.shape[0]}, expected {out_w}x{out_h}"
            )
        layer_rgb[ci] = arr.astype(np.float64)
        layer_mask[ci] = arr.max(axis=2) > lum_thresh
    blended = np.array(Image.open(blended_ref_path(layers_dir)).convert("RGB")).astype(np.float64)
    if blended.shape[:2] != (out_h, out_w):
        raise ValueError(
            f"blended pano is {blended.shape[1]}x{blended.shape[0]}, expected {out_w}x{out_h}"
        )
    return layer_rgb, layer_mask, blended


def derive_ptgui_weights(
    serials: List[str],
    layers_dir: Path,
    out_w: int,
    out_h: int,
    geom_weights: np.ndarray,
    dd_min: float = 12.0,
) -> np.ndarray:
    """
    Per-pano-pixel blend weights matching PTGUI's seam + feather.

    Derived from the take2 layer exports (camera order = PTGUI imagegroup order):
      - 0 cameras cover  -> weight 0 (black, no contribution)
      - 1 camera covers   -> weight 1.0 for that camera
      - 2 cameras (A,B)   -> solve blend = wA*A + (1-wA)*B (least squares over RGB), clamp [0,1]
      - >=3 cameras       -> hard pick the layer whose color is closest to the blend

    dd_min guards the feather solve where |A-B| is tiny (degenerate); there the
    weight is irrelevant so we fall back to the geometric winner.
    """
    num_cams = len(serials)
    layer_rgb, masks, blended = load_ptgui_layers(serials, layers_dir, out_w, out_h)
    count = masks.sum(axis=0)
    weights = np.zeros((num_cams, out_h, out_w), dtype=np.float32)

    rows = np.arange(out_h)[:, None].repeat(out_w, axis=1)
    cols = np.arange(out_w)[None, :].repeat(out_h, axis=0)

    # 1-cover: single owner.
    single = count == 1
    if np.any(single):
        idx_single = masks.argmax(axis=0)
        for ci in range(num_cams):
            weights[ci][single & (idx_single == ci)] = 1.0

    # 2-cover: solve blend = wA*A + (1-wA)*B.
    two = count == 2
    if np.any(two):
        first_idx = masks.argmax(axis=0)
        masks_wo_first = masks.copy()
        masks_wo_first[first_idx, rows, cols] = False
        second_idx = masks_wo_first.argmax(axis=0)

        a = layer_rgb[first_idx, rows, cols]
        b = layer_rgb[second_idx, rows, cols]
        d = a - b
        e = blended - b
        dd = np.sum(d * d, axis=2)
        de = np.sum(d * e, axis=2)
        wA = np.where(dd > dd_min, de / np.maximum(dd, 1e-9), np.nan)
        # Degenerate (|A-B| tiny): fall back to geometric winner's share.
        geom_first = geom_weights[first_idx, rows, cols]
        wA = np.where(np.isnan(wA), geom_first, wA)
        wA = np.clip(wA, 0.0, 1.0)

        for ci in range(num_cams):
            sel_a = two & (first_idx == ci)
            weights[ci][sel_a] = wA[sel_a].astype(np.float32)
            sel_b = two & (second_idx == ci)
            weights[ci][sel_b] = (1.0 - wA)[sel_b].astype(np.float32)

    # >=3 cover (not expected for this rig): hard pick layer closest to blend.
    multi = count >= 3
    if np.any(multi):
        dists = np.full((num_cams, out_h, out_w), np.inf, dtype=np.float64)
        for ci in range(num_cams):
            d = np.abs(layer_rgb[ci] - blended).mean(axis=2)
            dists[ci] = np.where(masks[ci], d, np.inf)
        win = dists.argmin(axis=0)
        for ci in range(num_cams):
            weights[ci][multi & (win == ci)] = 1.0

    return weights


def derive_feather_weights(
    serials: List[str],
    layers_dir: Path,
    out_w: int,
    out_h: int,
) -> np.ndarray:
    """
    Distance-based gradient feather (smooth cross-fade over the full overlap band).

    For each camera, the per-pixel weight is the Euclidean distance from that pixel
    to the nearest edge of the camera's coverage (0 at the footprint border, growing
    inward). Weights are then normalized across whichever cameras cover the pixel:

        w_i(p) = dist_i(p) / sum_j dist_j(p)

    - 0 cameras cover  -> 0
    - 1 camera covers   -> 1.0 (only one nonzero term)
    - 2+ cameras overlap -> a smooth ramp across the whole overlap region, so the
      seam is averaged over a gradient instead of a hard cut + narrow feather.
    """
    from scipy.ndimage import distance_transform_edt

    num_cams = len(serials)
    _, masks, _ = load_ptgui_layers(serials, layers_dir, out_w, out_h)
    dist = np.zeros((num_cams, out_h, out_w), dtype=np.float64)
    for ci in range(num_cams):
        dist[ci] = distance_transform_edt(masks[ci])
    wsum = dist.sum(axis=0)
    weights = np.zeros((num_cams, out_h, out_w), dtype=np.float32)
    nz = wsum > 0.0
    for ci in range(num_cams):
        weights[ci][nz] = (dist[ci][nz] / wsum[nz]).astype(np.float32)
    return weights


def render_from_lut(
    src_x: np.ndarray,
    src_y: np.ndarray,
    ci: int,
    source: np.ndarray,
    mask: Optional[np.ndarray] = None,
) -> np.ndarray:
    from scipy.ndimage import map_coordinates

    h, w = src_x.shape[1], src_x.shape[2]
    m = src_x[ci] >= 0.0
    if mask is not None:
        m = m & mask
    out = np.zeros((h, w, 3), dtype=np.float64)
    if not np.any(m):
        return out.astype(np.uint8)
    coords = np.array([src_y[ci], src_x[ci]])
    for ch in range(3):
        samp = map_coordinates(source[:, :, ch].astype(np.float64), coords, order=1, mode="constant", cval=0)
        out[:, :, ch] = np.where(m, samp, 0.0)
    return np.clip(out, 0, 255).astype(np.uint8)


def render_stitch(
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
    sources: List[np.ndarray],
) -> np.ndarray:
    from scipy.ndimage import map_coordinates

    num_cams, h, w = src_x.shape
    out = np.zeros((h, w, 3), dtype=np.float64)
    wsum = np.zeros((h, w), dtype=np.float64)
    for ci in range(num_cams):
        m = (weights[ci] > 0.0) & (src_x[ci] >= 0.0)
        if not np.any(m):
            continue
        coords = np.array([src_y[ci], src_x[ci]])
        for ch in range(3):
            samp = map_coordinates(sources[ci][:, :, ch].astype(np.float64), coords, order=1, mode="constant", cval=0)
            out[:, :, ch] += samp * weights[ci] * m
        wsum += weights[ci] * m
    wsum = np.maximum(wsum, 1e-9)
    out /= wsum[:, :, None]
    return np.clip(out, 0, 255).astype(np.uint8)


def masked_mse(a: np.ndarray, b: np.ndarray, thresh: int = 8) -> Tuple[float, float, int]:
    mask = (a.max(axis=2) > thresh) & (b.max(axis=2) > thresh)
    n = int(mask.sum())
    if n < 100:
        return 999.0, 999.0, n
    d = a[mask].astype(np.float64) - b[mask].astype(np.float64)
    mse = float(np.mean(d * d) / (255.0 * 255.0))
    mad = float(np.mean(np.abs(d)))
    return mse, mad, n


def layer_ref_path(layers_dir: Path, ci: int) -> Path:
    for pat in (f"EA6462986 Panorama{ci:04d}.jpg", f"*Panorama{ci:04d}.jpg"):
        hits = list(layers_dir.glob(pat))
        if hits:
            return hits[0]
    raise FileNotFoundError(f"layer {ci} not in {layers_dir}")


def blended_ref_path(layers_dir: Path) -> Path:
    for pat in ("EA6462986 Panorama.jpg", "*Panorama.jpg"):
        hits = [p for p in layers_dir.glob(pat) if "0000" not in p.name]
        if hits:
            return hits[0]
    raise FileNotFoundError(f"blended pano not in {layers_dir}")


def compare_layers(
    src_x: np.ndarray,
    src_y: np.ndarray,
    serials: List[str],
    layers_dir: Path,
    images_dir: Path,
) -> Dict[str, float]:
    out: Dict[str, float] = {}
    total_mse = 0.0
    for ci, serial in enumerate(serials):
        source = np.array(Image.open(images_dir / f"{serial}.jpg").convert("RGB"))
        ref = np.array(Image.open(layer_ref_path(layers_dir, ci)).convert("RGB"))
        ref_mask = ref.max(axis=2) > 8
        rend = render_from_lut(src_x, src_y, ci, source, mask=ref_mask)
        mse, mad, n = masked_mse(rend, ref)
        out[f"layer{ci}_{serial}_mse"] = mse
        out[f"layer{ci}_{serial}_mad"] = mad
        total_mse += mse
    out["layer_mean_mse"] = total_mse / max(len(serials), 1)
    return out


def conv_tag(conv: Convention) -> str:
    return (
        f"{conv.rot_order}_rt{int(conv.use_rt)}_shift{conv.shift_mode}_dist{conv.distort_mode}_"
        f"vlin{int(conv.pano_v_linear)}_ydown{int(conv.pano_y_down)}_"
        f"yflip{int(conv.proj_y_flip)}_ctr{int(conv.center_at_half)}"
    )


def sweep_conventions(
    project: dict, out_w: int, out_h: int, layers_dir: Path, images_dir: Path
) -> Tuple[Convention, Dict[str, float]]:
    toggles: List[Convention] = []
    # Lock pano/rotation to proof1 winner; sweep lens application (shift + distortion).
    base = dict(
        rot_order="YXZ",
        use_rt=True,
        pano_v_linear=False,
        pano_y_down=False,
        yaw_sign=1.0,
        pitch_sign=1.0,
        roll_sign=1.0,
    )
    for shift_mode in ("ptgui_sides", "panotools_diag"):
        for distort_mode in ("pixel_pano", "angular"):
            for proj_y_flip in (True, False):
                for center_at_half in (True, False):
                    for shift_sign in (1.0, -1.0):
                        toggles.append(
                            Convention(
                                shift_mode=shift_mode,
                                distort_mode=distort_mode,
                                proj_y_flip=proj_y_flip,
                                center_at_half=center_at_half,
                                shift_sign=shift_sign,
                                **base,
                            )
                        )

    best_conv = Convention()
    best_metrics = {"layer_mean_mse": 1e30}
    print(f"Sweeping {len(toggles)} conventions vs PTGUI layers...")
    for i, conv in enumerate(toggles):
        sx, sy, _, serials = bake_lut(project, out_w, out_h, conv)
        metrics = compare_layers(sx, sy, serials, layers_dir, images_dir)
        if metrics["layer_mean_mse"] < best_metrics["layer_mean_mse"]:
            best_metrics = metrics
            best_conv = conv
            print(f"  [{i+1}/{len(toggles)}] NEW BEST mse={metrics['layer_mean_mse']:.5f}  {conv_tag(conv)}")

    print(f"Best layer mean MSE={best_metrics['layer_mean_mse']:.5f}")
    return best_conv, best_metrics


def save_side_by_side(left: np.ndarray, right: np.ndarray, path: Path, label_l: str, label_r: str) -> None:
    h = max(left.shape[0], right.shape[0])
    gap = 8
    canvas = np.zeros((h, left.shape[1] + gap + right.shape[1], 3), dtype=np.uint8)
    canvas[: left.shape[0], : left.shape[1]] = left
    canvas[: right.shape[0], left.shape[1] + gap :] = right
    img = Image.fromarray(canvas)
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, left.shape[1], 14], fill=(0, 0, 0))
    draw.rectangle([left.shape[1] + gap, 0, canvas.shape[1], 14], fill=(0, 0, 0))
    draw.text((4, 1), label_l, fill=(255, 255, 255))
    draw.text((left.shape[1] + gap + 4, 1), label_r, fill=(255, 255, 255))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, quality=92)


def save_diff_heatmap(ours: np.ndarray, ref: np.ndarray, path: Path) -> None:
    mask = ref.max(axis=2) > 8
    diff = np.zeros(ref.shape[:2], dtype=np.float64)
    if mask.any():
        diff[mask] = np.abs(ours[mask].astype(np.float64) - ref[mask].astype(np.float64)).mean(axis=1)
    scale = np.percentile(diff[mask], 99) if mask.any() else 1.0
    scale = max(scale, 1.0)
    heat = np.clip(diff / scale, 0, 1)
    rgb = np.zeros((ref.shape[0], ref.shape[1], 3), dtype=np.uint8)
    rgb[..., 0] = (heat * 255).astype(np.uint8)
    rgb[..., 2] = ((1.0 - heat) * 80).astype(np.uint8)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgb).save(path)


def seam_band_mask(layers_dir: Path, serials: List[str], out_w: int, out_h: int) -> np.ndarray:
    """Pano pixels where 2+ PTGUI layers overlap (the seam/feather band)."""
    _, masks, _ = load_ptgui_layers(serials, layers_dir, out_w, out_h)
    return masks.sum(axis=0) >= 2


def masked_mad_on(a: np.ndarray, b: np.ndarray, region: np.ndarray, thresh: int = 8) -> Tuple[float, int]:
    mask = region & (a.max(axis=2) > thresh) & (b.max(axis=2) > thresh)
    n = int(mask.sum())
    if n < 50:
        return 0.0, n
    d = np.abs(a[mask].astype(np.float64) - b[mask].astype(np.float64))
    return float(np.mean(d)), n


def write_proof_bundle(
    proof_dir: Path,
    conv: Convention,
    metrics: Dict[str, float],
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
    serials: List[str],
    layers_dir: Path,
    images_dir: Path,
    seam_mode: str = "ptgui_blend",
) -> None:
    proof_dir.mkdir(parents=True, exist_ok=True)
    sources = [np.array(Image.open(images_dir / f"{s}.jpg").convert("RGB")) for s in serials]

    pano_ref = np.array(Image.open(blended_ref_path(layers_dir)).convert("RGB"))
    pano_ours = render_stitch(src_x, src_y, weights, sources)
    mse, mad, _ = masked_mse(pano_ours, pano_ref)
    metrics = dict(metrics)
    metrics["pano_mse"] = mse
    metrics["pano_mad"] = mad
    metrics["seam_mode"] = seam_mode
    metrics["convention"] = conv.__dict__

    # Seam-band MAD: error measured only where cameras overlap (the seam/feather band).
    band = seam_band_mask(layers_dir, serials, src_x.shape[2], src_x.shape[1])
    seam_mad, seam_n = masked_mad_on(pano_ours, pano_ref, band)
    metrics["seam_band_mad"] = seam_mad
    metrics["seam_band_px"] = seam_n
    save_diff_heatmap(
        np.where(band[:, :, None], pano_ours, 0),
        np.where(band[:, :, None], pano_ref, 0),
        proof_dir / "pano_seam_band_diff.jpg",
    )

    Image.fromarray(pano_ours).save(proof_dir / "pano_ours.jpg", quality=92)
    Image.fromarray(pano_ref).save(proof_dir / "pano_ptgui.jpg", quality=92)
    save_side_by_side(pano_ours, pano_ref, proof_dir / "pano_side_by_side.jpg", "OURS (LUT stitch)", "PTGUI blend")
    save_diff_heatmap(pano_ours, pano_ref, proof_dir / "pano_diff_heat.jpg")

    for ci, serial in enumerate(serials):
        ref = np.array(Image.open(layer_ref_path(layers_dir, ci)).convert("RGB"))
        ref_mask = ref.max(axis=2) > 8
        ours = render_from_lut(src_x, src_y, ci, sources[ci], mask=ref_mask)
        lmse, lmad, _ = masked_mse(ours, ref)
        metrics[f"layer{ci}_serial"] = serial
        metrics[f"layer{ci}_mse"] = lmse
        metrics[f"layer{ci}_mad"] = lmad
        Image.fromarray(ours).save(proof_dir / f"layer{ci}_{serial}_ours.jpg", quality=92)
        Image.fromarray(ref).save(proof_dir / f"layer{ci}_{serial}_ptgui.jpg", quality=92)
        save_side_by_side(
            ours, ref, proof_dir / f"layer{ci}_{serial}_side_by_side.jpg", f"OURS {serial}", "PTGUI layer"
        )
        save_diff_heatmap(ours, ref, proof_dir / f"layer{ci}_{serial}_diff.jpg")

    (proof_dir / "validation.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")

    lines = [
        "PTGUI warp validation (pre-deploy proof)",
        f"Convention: {conv_tag(conv)}",
        f"Lens: shift={conv.shift_mode} distort={conv.distort_mode} (PTGUI .pts a/b/c/focal/shift)",
        f"Seam mode: {seam_mode}",
        f"Pano vs PTGUI blend: MSE={metrics['pano_mse']:.5f}  MAD={metrics['pano_mad']:.2f}px",
        f"Seam-band MAD (overlap only, {metrics['seam_band_px']}px): {metrics['seam_band_mad']:.2f}px",
        f"Layer mean MSE: {metrics.get('layer_mean_mse', metrics['pano_mse']):.5f}",
        "",
        "Per layer:",
    ]
    for ci, serial in enumerate(serials):
        lines.append(
            f"  [{ci}] {serial}: MSE={metrics[f'layer{ci}_mse']:.5f}  MAD={metrics[f'layer{ci}_mad']:.2f}px"
        )
    lines += ["", "Open pano_side_by_side.jpg, pano_seam_band_diff.jpg and layer*_side_by_side.jpg to eyeball."]
    (proof_dir / "README.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def main() -> int:
    ap = argparse.ArgumentParser(description="Bake warp LUT from .pts forward projection")
    ap.add_argument("pts", type=Path)
    ap.add_argument("--out", type=Path, default=Path("stitch-calib"))
    ap.add_argument("--images", type=Path, required=True, help="Source {serial}.jpg tiles")
    ap.add_argument("--layers", type=Path, required=True, help="PTGUI layer export dir")
    ap.add_argument("--proof", type=Path, default=Path("stitch-calib/proof"), help="Write validation images here")
    ap.add_argument("--width", type=int, default=0)
    ap.add_argument("--height", type=int, default=0)
    ap.add_argument("--sweep", action="store_true", help="Sweep conventions against PTGUI layers")
    ap.add_argument("--from-proof", type=Path, help="Load winning convention from proof/validation.json")
    ap.add_argument("--no-write-lut", action="store_true", help="Proof only; skip warp_lut.bin")
    ap.add_argument(
        "--seam",
        choices=("ptgui_blend", "feather", "geometric"),
        default="ptgui_blend",
        help="Seam weights: ptgui_blend (match PTGUI cut), feather (distance gradient "
        "cross-fade over full overlap), geometric (argmax ray z)",
    )
    args = ap.parse_args()

    if Image is None:
        raise SystemExit("Pillow required")

    project = load_pts_project(args.pts)
    if args.width > 0 and args.height > 0:
        out_w, out_h = args.width, args.height
    else:
        im = Image.open(blended_ref_path(args.layers))
        out_w, out_h = im.size

    conv = Convention()
    metrics: Dict[str, float] = {}
    if args.from_proof:
        vdata = json.loads((args.from_proof / "validation.json").read_text(encoding="utf-8"))
        raw = dict(vdata["convention"])
        legacy_shift = raw.pop("shift_scale", None)
        raw.pop("distort_forward", None)
        if "shift_mode" not in raw:
            raw["shift_mode"] = (
                "ptgui_sides" if legacy_shift in ("width_frac", "width_pct") else "panotools_diag"
            )
        if "distort_mode" not in raw:
            raw["distort_mode"] = "pixel_pano"
        conv = Convention(**{k: v for k, v in raw.items() if k in Convention.__dataclass_fields__})
        metrics = {k: v for k, v in vdata.items() if k != "convention" and isinstance(v, (int, float))}
        print(f"Loaded convention from {args.from_proof}: {conv_tag(conv)}")
    elif args.sweep:
        conv, metrics = sweep_conventions(project, out_w, out_h, args.layers, args.images)
    else:
        sx, sy, _, serials = bake_lut(project, out_w, out_h, conv)
        metrics = compare_layers(sx, sy, serials, args.layers, args.images)

    src_x, src_y, weights, serials = bake_lut(project, out_w, out_h, conv)
    if args.seam == "ptgui_blend":
        weights = derive_ptgui_weights(serials, args.layers, out_w, out_h, weights)
        print("Seam weights: ptgui_blend (derived from PTGUI layer exports)")
    elif args.seam == "feather":
        weights = derive_feather_weights(serials, args.layers, out_w, out_h)
        print("Seam weights: feather (distance gradient cross-fade over overlap)")
    else:
        print("Seam weights: geometric (argmax ray z)")
    write_proof_bundle(
        args.proof, conv, metrics, src_x, src_y, weights, serials, args.layers, args.images, seam_mode=args.seam
    )

    if args.no_write_lut:
        print(f"Proof written to {args.proof} (no LUT deployed)")
        return 0

    slot_serials, src_x, src_y, weights, _ = reorder_by_slot(serials, src_x, src_y, weights)
    args.out.mkdir(parents=True, exist_ok=True)
    data = calib_to_dict(parse_pts(args.pts))
    data["output_width"] = out_w
    data["output_height"] = out_h
    data["slot_serials"] = slot_serials
    data["bake_source"] = "pts_forward"
    data["convention"] = conv.__dict__
    for serial in data.get("cameras", {}):
        data["cameras"][serial]["yaw_offset_deg"] = 0.0
        data["cameras"][serial]["pitch_offset_deg"] = 0.0

    calib_json = args.out / "calib.json"
    calib_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    calib_hash = hashlib.sha256(calib_json.read_bytes()).hexdigest()[:16]
    data["calib_hash"] = calib_hash
    calib_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    write_warp_lut(args.out, slot_serials, out_w, out_h, src_x, src_y, weights, calib_hash)
    write_seam_masks(args.out, weights, 4, out_w, out_h)
    write_eq_seam_samples(args.out, slot_serials, list(range(4)), src_x, src_y, weights, out_w, out_h)
    stitch_preview_jpeg(args.images, slot_serials, out_w, out_h, src_x, src_y, weights, args.out / "preview.jpg")

    print(f"\nBaked {out_w}x{out_h} -> {args.out}")
    print(f"Proof bundle -> {args.proof}")
    print(f"calib_hash={calib_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
