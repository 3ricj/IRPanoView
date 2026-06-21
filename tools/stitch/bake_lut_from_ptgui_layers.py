#!/usr/bin/env python3
"""Build warp_lut.bin from PTGUI individual layer exports (no projection math)."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import List, Tuple

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from bake_stitch_luts import (
    reorder_by_slot,
    stitch_preview_jpeg,
    write_eq_seam_samples,
    write_seam_masks,
    write_warp_lut,
)
from parse_ptgui_pts import calib_to_dict, parse_pts

try:
    from PIL import Image
except ImportError:
    Image = None  # type: ignore

LAYER_RE = re.compile(r"^(?P<base>.+?)(?P<idx>\d{4})\.jpg$", re.IGNORECASE)


def ptgui_layer_serials(pts_path: Path) -> List[str]:
    calib = parse_pts(pts_path)
    groups = sorted(calib.cameras.values(), key=lambda c: c.ptgui_index)
    return [c.serial for c in groups]


def discover_layers(layers_dir: Path) -> Tuple[Path, List[Tuple[int, Path]]]:
    """Return (blended_pano_path, [(ptgui_index, layer_path), ...])."""
    blended = None
    indexed: List[Tuple[int, Path]] = []
    for path in sorted(layers_dir.glob("*.jpg")):
        m = LAYER_RE.match(path.name)
        if m:
            indexed.append((int(m.group("idx")), path))
        elif path.name.lower().endswith(".jpg"):
            blended = path
    indexed.sort(key=lambda t: t[0])
    if not indexed:
        raise FileNotFoundError(f"No PanoramaNNNN.jpg layer files in {layers_dir}")
    if blended is None:
        # Use lowest-index sibling without suffix if present
        base = indexed[0][1].name.rsplit("0", 1)[0]
        raise FileNotFoundError(f"No blended pano JPG found in {layers_dir}")
    return blended, indexed


def invert_layer_warp(source: np.ndarray, warped: np.ndarray, lum_thresh: float = 8.0) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Invert PTGUI layer warp: pano pixel -> source (x,y) via nearest RGB in source tile."""
    from scipy.spatial import cKDTree

    h, w, _ = warped.shape
    sh, sw = source.shape[:2]
    active = warped.max(axis=2) > lum_thresh
    if not np.any(active):
        src_x = np.full((h, w), -1.0, dtype=np.float32)
        src_y = np.full((h, w), -1.0, dtype=np.float32)
        return src_x, src_y, active

    source_flat = source.reshape(-1, 3).astype(np.float32)
    sy_idx, sx_idx = np.divmod(np.arange(source_flat.shape[0], dtype=np.int32), sw)
    tree = cKDTree(source_flat)

    pano_flat = warped.reshape(-1, 3).astype(np.float32)
    active_flat = active.ravel()
    nn = np.full(pano_flat.shape[0], -1, dtype=np.int32)
    nn[active_flat], _ = tree.query(pano_flat[active_flat], k=1, workers=-1)

    src_x = np.full((h, w), -1.0, dtype=np.float32)
    src_y = np.full((h, w), -1.0, dtype=np.float32)
    valid = nn >= 0
    src_x.flat[valid] = sx_idx[nn[valid]].astype(np.float32)
    src_y.flat[valid] = sy_idx[nn[valid]].astype(np.float32)
    return src_x, src_y, active


def pick_weights_from_blend(
    layers: np.ndarray,
    full: np.ndarray,
    src_x: np.ndarray,
    src_y: np.ndarray,
    active: np.ndarray,
) -> np.ndarray:
    """Hard pick one camera per pixel: closest layer color to PTGUI blended pano."""
    num_cams, h, w = src_x.shape
    err = np.sum((layers.astype(np.float32) - full.astype(np.float32)[None, :, :, :]) ** 2, axis=3)
    err[~active] = np.inf
    winner = err.argmin(axis=0)
    weights = np.zeros((num_cams, h, w), dtype=np.float32)
    for ci in range(num_cams):
        weights[ci] = (winner == ci).astype(np.float32)
    return weights


def bake_from_ptgui_layers(
    pts_path: Path,
    layers_dir: Path,
    images_dir: Path,
    out_dir: Path,
) -> None:
    if Image is None:
        raise SystemExit("Pillow required")

    blended_path, layer_paths = discover_layers(layers_dir)
    serials_ptgui = ptgui_layer_serials(pts_path)
    if len(layer_paths) != len(serials_ptgui):
        raise ValueError(
            f"Expected {len(serials_ptgui)} PTGUI layers, found {len(layer_paths)} in {layers_dir}"
        )

    full = np.array(Image.open(blended_path).convert("RGB"))
    height, width = full.shape[:2]

    num_cams = len(serials_ptgui)
    src_x = np.full((num_cams, height, width), -1.0, dtype=np.float32)
    src_y = np.full((num_cams, height, width), -1.0, dtype=np.float32)
    active = np.zeros((num_cams, height, width), dtype=bool)
    layers = np.zeros((num_cams, height, width, 3), dtype=np.uint8)

    for ci, (_, layer_path) in enumerate(layer_paths):
        serial = serials_ptgui[ci]
        source_path = images_dir / f"{serial}.jpg"
        if not source_path.exists():
            raise FileNotFoundError(f"Missing source tile for {serial}: {source_path}")
        source = np.array(Image.open(source_path).convert("RGB"))
        warped = np.array(Image.open(layer_path).convert("RGB"))
        if warped.shape[:2] != (height, width):
            raise ValueError(f"{layer_path.name} size {warped.shape[:2]} != pano {height}x{width}")
        layers[ci] = warped
        print(f"  inverting layer {ci} {serial} ({layer_path.name})...", flush=True)
        sx, sy, act = invert_layer_warp(source, warped)
        src_x[ci] = sx
        src_y[ci] = sy
        active[ci] = act
        covered = int(act.sum())
        print(f"  layer {ci} {serial} ({layer_path.name}): covered {covered}/{height * width} px")

    weights = pick_weights_from_blend(layers, full, src_x, src_y, active)

    slot_serials, src_x, src_y, weights, _ = reorder_by_slot(serials_ptgui, src_x, src_y, weights)

    out_dir.mkdir(parents=True, exist_ok=True)
    calib = parse_pts(pts_path)
    calib.output_width = width
    calib.output_height = height
    data = calib_to_dict(calib)
    data["output_width"] = width
    data["output_height"] = height
    data["camera_serials"] = slot_serials
    data["slot_serials"] = slot_serials
    data["bake_source"] = "ptgui_layers"
    data["ptgui_layers_dir"] = str(layers_dir)
    for serial in calib.cameras:
        if serial in data.get("cameras", {}):
            data["cameras"][serial]["yaw_offset_deg"] = 0.0
            data["cameras"][serial]["pitch_offset_deg"] = 0.0

    calib_json = out_dir / "calib.json"
    calib_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    calib_hash = hashlib.sha256(calib_json.read_bytes()).hexdigest()[:16]
    data["calib_hash"] = calib_hash
    calib_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    lut_path = write_warp_lut(out_dir, slot_serials, width, height, src_x, src_y, weights, calib_hash)
    write_seam_masks(out_dir, weights, 4, width, height)
    write_eq_seam_samples(out_dir, slot_serials, list(range(4)), src_x, src_y, weights, width, height)
    stitch_preview_jpeg(images_dir, slot_serials, width, height, src_x, src_y, weights, out_dir / "preview.jpg")

    # Compare preview to PTGUI blend.
    prev = np.array(Image.open(out_dir / "preview.jpg").convert("RGB"))
    mask = full.max(axis=2) > 5
    mse = np.mean(((prev[mask].astype(np.float64) - full[mask].astype(np.float64)) / 255.0) ** 2)
    mad = np.mean(np.abs(prev[mask].astype(np.float64) - full[mask].astype(np.float64)))
    print(f"Baked {width}x{height} from PTGUI layers -> {lut_path}")
    print(f"Serial order (USB slots 0..3): {slot_serials}")
    print(f"calib_hash={calib_hash}")
    print(f"Preview vs PTGUI blend: MSE={mse:.5f} mean_abs_diff={mad:.2f}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Bake warp LUT from PTGUI individual layer JPGs")
    ap.add_argument("pts", type=Path, help="PTGUI .pts project")
    ap.add_argument("--layers", type=Path, required=True, help="Dir with Panorama.jpg + PanoramaNNNN.jpg")
    ap.add_argument("--images", type=Path, required=True, help="Dir with source {serial}.jpg tiles")
    ap.add_argument("--out", type=Path, default=Path("stitch-calib"), help="Output stitch-calib dir")
    args = ap.parse_args()

    print(f"PTGUI layer bake: {args.layers}")
    bake_from_ptgui_layers(args.pts, args.layers, args.images, args.out)
    print(f"\nDeploy {args.out}/ to Pi and restart irpanoview-host --stitch-calib ~/stitch-calib --stitch-mode warp")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
