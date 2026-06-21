#!/usr/bin/env python3
"""Compare proof bundles: pano MAD, per-quadrant MAD, and seam-band MAD.

Seam-band MAD is recomputed from each bundle's own layer*_ptgui.jpg exports
(overlap = pixels covered by >=2 layers) so it is comparable across proof,
proof2, proof3 even if older bundles did not store the metric.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def overlap_mask(proof: Path, num_cams: int = 4, thresh: int = 8) -> np.ndarray:
    masks = []
    for ci in range(num_cams):
        hits = list(proof.glob(f"layer{ci}_*_ptgui.jpg"))
        if not hits:
            return np.zeros((0, 0), dtype=bool)
        arr = np.array(Image.open(hits[0]).convert("RGB"))
        masks.append(arr.max(axis=2) > thresh)
    return np.stack(masks).sum(axis=0) >= 2


def seam_band_mad(proof: Path, thresh: int = 8) -> tuple[float, int]:
    band = overlap_mask(proof)
    if band.size == 0:
        return float("nan"), 0
    ours = np.array(Image.open(proof / "pano_ours.jpg").convert("RGB"))
    ref = np.array(Image.open(proof / "pano_ptgui.jpg").convert("RGB"))
    mask = band & (ours.max(axis=2) > thresh) & (ref.max(axis=2) > thresh)
    n = int(mask.sum())
    if n < 50:
        return 0.0, n
    d = np.abs(ours[mask].astype(float) - ref[mask].astype(float))
    return float(np.mean(d)), n


def quad_report(proof: Path) -> None:
    v = json.loads((proof / "validation.json").read_text())
    seam_mad, seam_n = seam_band_mad(proof)
    seam_mode = v.get("seam_mode", "?")
    print(
        f"{proof}: pano MAD {v['pano_mad']:.2f}px  "
        f"seam-band MAD {seam_mad:.2f}px ({seam_n}px)  [seam={seam_mode}]"
    )
    for ci in range(4):
        serial = v[f"layer{ci}_serial"]
        ours = np.array(Image.open(proof / f"layer{ci}_{serial}_ours.jpg").convert("RGB"))
        ref = np.array(Image.open(proof / f"layer{ci}_{serial}_ptgui.jpg").convert("RGB"))
        diff = np.abs(ours.astype(float) - ref.astype(float)).mean(axis=2)
        mask = ref.max(axis=2) > 8
        h, w = diff.shape
        bl = diff[h // 2 :, : w // 2][mask[h // 2 :, : w // 2]]
        bl_str = f"{bl.mean():.1f}px" if bl.size else "n/a"
        print(f"  {serial}: bot_left {bl_str}  layer {v[f'layer{ci}_mad']:.1f}px")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        quad_report(Path(p))
