#!/usr/bin/env python3
"""Bake warp LUT binaries and preview JPEG from stitch-calib.json."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from panotools_math import FIXED_POINT, bake_warp_maps, compute_optimum_size
from parse_ptgui_pts import calib_from_dict
from fit_pose_offsets import apply_pose_offsets

try:
    from PIL import Image
except ImportError:
    Image = None  # type: ignore

WARP_MAGIC = b"IRPW"
WARP_VERSION = 1
HEADER_SIZE = 64

# Pi host pano left-to-right slot assignment by serial suffix.
SUFFIX_TO_SLOT = {"07": 0, "86": 1, "02": 2, "97": 3}


def serial_to_slot(serial: str) -> int:
    if len(serial) >= 2:
        suffix = serial[-2:]
        if suffix in SUFFIX_TO_SLOT:
            return SUFFIX_TO_SLOT[suffix]
    return -1


def reorder_by_slot(
    serials: List[str],
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
) -> Tuple[List[str], np.ndarray, np.ndarray, np.ndarray, List[int]]:
    """Return slot-ordered arrays [4,H,W] and slot_serials[4]."""
    num_cams = len(serials)
    h, w = src_x.shape[1], src_x.shape[2]
    slot_src_x = np.full((4, h, w), -1.0, dtype=np.float32)
    slot_src_y = np.full((4, h, w), -1.0, dtype=np.float32)
    slot_weights = np.zeros((4, h, w), dtype=np.float32)
    slot_serials = [""] * 4
    calib_to_slot = []
    for ci, serial in enumerate(serials):
        slot = serial_to_slot(serial)
        calib_to_slot.append(slot)
        if slot < 0 or slot >= 4:
            raise ValueError(f"serial {serial} has no known USB slot suffix")
        slot_serials[slot] = serial
        slot_src_x[slot] = src_x[ci]
        slot_src_y[slot] = src_y[ci]
        slot_weights[slot] = weights[ci]
    for s in range(4):
        if not slot_serials[s]:
            raise ValueError(f"no camera mapped to slot {s + 1}")
    return slot_serials, slot_src_x, slot_src_y, slot_weights, calib_to_slot


def write_warp_lut(
    out_dir: Path,
    serials: List[str],
    width: int,
    height: int,
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
    calib_hash: str,
) -> Path:
    """Write combined warp_lut.bin (4 entries per pixel in USB slot order 0..3)."""
    num_cams = 4
    path = out_dir / "warp_lut.bin"
    serial_blob = "\n".join(serials).encode("utf-8")

    header = bytearray(HEADER_SIZE)
    header[0:4] = WARP_MAGIC
    struct.pack_into("<I", header, 4, WARP_VERSION)
    struct.pack_into("<I", header, 8, width)
    struct.pack_into("<I", header, 12, height)
    struct.pack_into("<I", header, 16, num_cams)
    struct.pack_into("<I", header, 20, len(serial_blob))
    # calib hash as 8 bytes of sha256
    digest = hashlib.sha256(calib_hash.encode()).digest()
    header[24:32] = digest[:8]

    with path.open("wb") as f:
        f.write(header)
        f.write(serial_blob)
        # Pixel-major order: for each pano pixel, 4 slot entries (matches warp_stitch.cpp).
        for row in range(height):
            for col in range(width):
                for ci in range(num_cams):
                    w = weights[ci, row, col]
                    w_byte = int(round(max(0.0, min(1.0, w)) * 255.0))
                    sx = src_x[ci, row, col]
                    sy = src_y[ci, row, col]
                    if w_byte == 0 or sx < 0:
                        f.write(struct.pack("<Bii", 0, 0, 0))
                    else:
                        f.write(struct.pack(
                            "<Bii",
                            w_byte,
                            int(round(sx * FIXED_POINT)),
                            int(round(sy * FIXED_POINT)),
                        ))
    _verify_warp_lut_layout(path, width, height, src_x, src_y, weights, num_cams)
    return path


def _verify_warp_lut_layout(
    path: Path,
    width: int,
    height: int,
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
    num_cams: int,
) -> None:
    """Ensure on-disk order matches warp_stitch.cpp (pixel-major, 4 slots per pixel)."""
    data = path.read_bytes()
    serial_len = struct.unpack_from("<I", data, 20)[0]
    off = 64 + serial_len
    for row, col, ci in ((0, 0, 0), (0, 1, 0), (3, 50, 2)):
        pix_idx = row * width + col
        entry_off = off + pix_idx * num_cams * 9 + ci * 9
        w0, sx0, sy0 = struct.unpack_from("<Bii", data, entry_off)
        expected_w = int(round(max(0.0, min(1.0, weights[ci, row, col])) * 255.0))
        if src_x[ci, row, col] < 0 or expected_w == 0:
            continue
        expected_sx = int(round(src_x[ci, row, col] * FIXED_POINT))
        expected_sy = int(round(src_y[ci, row, col] * FIXED_POINT))
        if w0 != expected_w or sx0 != expected_sx or sy0 != expected_sy:
            raise RuntimeError(
                f"warp_lut.bin layout mismatch at ({row},{col}) slot{ci}: "
                f"file=({w0},{sx0},{sy0}) expected=({expected_w},{expected_sx},{expected_sy})"
            )


def write_eq_seam_samples(
    out_dir: Path,
    serials: List[str],
    slot_order: List[int],
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
    width: int,
    height: int,
    max_per_seam: int = 4096,
) -> Path:
    """Radiometric equalizer sample pairs: bilinear coords on both slots at overlap."""
    path = out_dir / "eq_seam_samples.bin"
    num_cams = len(serials)
    # Map calib serial index -> USB slot (0-based) using suffix order 07,86,02,97
    suffix_to_slot = {"07": 0, "86": 1, "02": 2, "97": 3}

    with path.open("wb") as f:
        f.write(struct.pack("<II", num_cams, 3))  # 3 seams between 4 cams
        for seam in range(3):
            left_slot = seam
            right_slot = seam + 1
            samples = []
            for row in range(height):
                for col in range(width):
                    wl = weights[left_slot, row, col]
                    wr = weights[right_slot, row, col]
                    if wl < 0.15 or wr < 0.15 or wl > 0.85 or wr > 0.85:
                        continue
                    sxl = src_x[left_slot, row, col]
                    syl = src_y[left_slot, row, col]
                    sxr = src_x[right_slot, row, col]
                    syr = src_y[right_slot, row, col]
                    if sxl < 0 or sxr < 0:
                        continue
                    samples.append((
                        int(round(sxl * FIXED_POINT)),
                        int(round(syl * FIXED_POINT)),
                        int(round(sxr * FIXED_POINT)),
                        int(round(syr * FIXED_POINT)),
                    ))
            if len(samples) > max_per_seam:
                step = max(1, len(samples) // max_per_seam)
                samples = samples[::step][:max_per_seam]
            f.write(struct.pack("<III", seam, left_slot, right_slot))
            f.write(struct.pack("<I", len(samples)))
            for sxl, syl, sxr, syr in samples:
                f.write(struct.pack("<iiii", sxl, syl, sxr, syr))
    return path


def write_seam_masks(out_dir: Path, weights: np.ndarray, num_cams: int, width: int, height: int) -> Path:
    """Seam mask: per pixel, uint8 pair indices (cam_a, cam_b) where both have weight in [0.15, 0.85]."""
    path = out_dir / "seam_masks.bin"
    with path.open("wb") as f:
        f.write(struct.pack("<II", width, height))
        for row in range(height):
            for col in range(width):
                active = [(ci, weights[ci, row, col]) for ci in range(num_cams) if weights[ci, row, col] > 0.05]
                if len(active) < 2:
                    f.write(struct.pack("<BB", 255, 255))
                    continue
                active.sort(key=lambda t: t[1], reverse=True)
                a, b = active[0][0], active[1][0]
                wa, wb = active[0][1], active[1][1]
                if 0.15 <= wa <= 0.85 and 0.15 <= wb <= 0.85:
                    f.write(struct.pack("<BB", a, b))
                else:
                    f.write(struct.pack("<BB", 255, 255))
    return path


def bilinear_sample_u8(img: np.ndarray, x: float, y: float) -> Tuple[float, float, float]:
    h, w, _ = img.shape
    x = max(0.0, min(x, w - 1.001))
    y = max(0.0, min(y, h - 1.001))
    x0 = int(x)
    y0 = int(y)
    fx = x - x0
    fy = y - y0
    x1 = min(x0 + 1, w - 1)
    y1 = min(y0 + 1, h - 1)
    out = np.zeros(3, dtype=np.float64)
    for dy, ty in ((0, 1.0 - fy), (1, fy)):
        for dx, tx in ((0, 1.0 - fx), (1, fx)):
            out += img[y0 + dy, x0 + dx].astype(np.float64) * tx * ty
    return float(out[0]), float(out[1]), float(out[2])


def stitch_preview_jpeg(
    images_dir: Path,
    serials: List[str],
    width: int,
    height: int,
    src_x: np.ndarray,
    src_y: np.ndarray,
    weights: np.ndarray,
    out_path: Path,
) -> None:
    if Image is None:
        print("Pillow not installed; skipping preview JPEG")
        return
    num_cams = len(serials)
    imgs = []
    for serial in serials:
        p = images_dir / f"{serial}.jpg"
        if not p.exists():
            raise FileNotFoundError(f"Missing image: {p}")
        imgs.append(np.array(Image.open(p).convert("RGB")))

    out = np.zeros((height, width, 3), dtype=np.float64)
    for row in range(height):
        for col in range(width):
            acc = np.zeros(3, dtype=np.float64)
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
                acc += np.array([r, g, b]) * w
                wsum += w
            if wsum > 0:
                out[row, col] = acc / wsum
    Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(out_path, quality=92)
    print(f"Wrote preview {out_path}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Bake warp LUTs from calib.json")
    ap.add_argument("calib", type=Path, help="stitch-calib/calib.json")
    ap.add_argument("--images", type=Path, help="Directory with {serial}.jpg eq samples")
    ap.add_argument("--out", type=Path, required=True, help="Output directory")
    ap.add_argument("--width", type=int, default=0, help="Force output width")
    ap.add_argument("--height", type=int, default=0, help="Force output height")
    ap.add_argument("--no-fit-yaw", action="store_true", help="Skip CP yaw offset fit")
    args = ap.parse_args()

    data = json.loads(args.calib.read_text(encoding="utf-8"))
    calib = calib_from_dict(data)
    w = args.width or calib.output_width or 0
    h = args.height or calib.output_height or 0
    if w <= 0 or h <= 0:
        w, h = compute_optimum_size(calib)

    if not args.no_fit_yaw and calib.control_points:
        rms = apply_pose_offsets(calib, w, h)
        parts = []
        for serial in sorted(calib.cameras.keys(), key=lambda s: calib.cameras[s].ptgui_index):
            cam = calib.cameras[serial]
            parts.append(
                f"{serial}(y={cam.yaw_offset_deg:+.2f},p={cam.pitch_offset_deg:+.2f})"
            )
        print(f"Applied CP pose offsets mean={rms:.2f}px: " + ", ".join(parts))

    src_x, src_y, weights, serials = bake_warp_maps(calib, w, h)
    slot_serials, src_x, src_y, weights, _ = reorder_by_slot(serials, src_x, src_y, weights)
    serials = slot_serials
    calib.output_width = w
    calib.output_height = h

    args.out.mkdir(parents=True, exist_ok=True)
    calib_json = args.out / "calib.json"
    data["output_width"] = w
    data["output_height"] = h
    data["camera_serials"] = serials
    data["slot_serials"] = serials  # slot order 0..3
    for serial, cam in calib.cameras.items():
        if serial in data.get("cameras", {}):
            data["cameras"][serial]["yaw_offset_deg"] = cam.yaw_offset_deg
            data["cameras"][serial]["pitch_offset_deg"] = cam.pitch_offset_deg
    calib_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    calib_hash = hashlib.sha256(calib_json.read_bytes()).hexdigest()[:16]
    data["calib_hash"] = calib_hash
    calib_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    lut_path = write_warp_lut(args.out, serials, w, h, src_x, src_y, weights, calib_hash)
    seam_path = write_seam_masks(args.out, weights, len(serials), w, h)
    eq_path = write_eq_seam_samples(args.out, serials, list(range(len(serials))), src_x, src_y, weights, w, h)
    print(f"Eq seam samples -> {eq_path}")
    print(f"Baked {w}x{h} LUT -> {lut_path} ({lut_path.stat().st_size} bytes)")
    print(f"Seam masks -> {seam_path}")
    print(f"Serial order: {serials}")
    print(f"calib_hash={calib_hash}")

    if args.images:
        stitch_preview_jpeg(
            args.images, serials, w, h, src_x, src_y, weights,
            args.out / "preview.jpg",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
