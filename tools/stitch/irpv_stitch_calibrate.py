#!/usr/bin/env python3
"""Run full PTGUI -> stitch-calib pipeline."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent


def run(cmd: list) -> None:
    print("+", " ".join(str(c) for c in cmd))
    subprocess.check_call(cmd)


def main() -> int:
    ap = argparse.ArgumentParser(description="IRPanoView stitch calibration pipeline")
    ap.add_argument("pts", type=Path, help="PTGUI .pts file")
    ap.add_argument("--out", type=Path, default=Path("stitch-calib"), help="Output directory")
    ap.add_argument("--images", type=Path, help="eq-sample JPG directory")
    ap.add_argument(
        "--ptgui-layers",
        type=Path,
        help="PTGUI individual layer export dir (Panorama.jpg + PanoramaNNNN.jpg)",
    )
    args = ap.parse_args()

    py = sys.executable
    if args.ptgui_layers:
        if not args.images:
            ap.error("--images is required with --ptgui-layers")
        run([
            py,
            str(SCRIPT_DIR / "bake_lut_from_ptgui_layers.py"),
            str(args.pts),
            "--layers",
            str(args.ptgui_layers),
            "--images",
            str(args.images),
            "--out",
            str(args.out),
        ])
        print(f"\nDone. Deploy {args.out}/ to Pi and restart irpanoview-host --stitch-calib {args.out}")
        return 0

    run([py, str(SCRIPT_DIR / "parse_ptgui_pts.py"), str(args.pts), "--out", str(args.out)])
    bake_cmd = [py, str(SCRIPT_DIR / "bake_stitch_luts.py"), str(args.out / "calib.json"), "--out", str(args.out)]
    if args.images:
        bake_cmd.extend(["--images", str(args.images)])
    run(bake_cmd)
    run([py, str(SCRIPT_DIR / "validate_pts.py"), str(args.out), "--pts", str(args.pts)])
    print(f"\nDone. Deploy {args.out}/ to Pi and restart irpanoview-host --stitch-calib {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
