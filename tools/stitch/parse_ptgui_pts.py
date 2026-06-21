#!/usr/bin/env python3
"""Parse PTGUI JSON .pts (v55+) into IRPanoView stitch-calib.json."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from panotools_math import CameraPose, LensParams, PanoParams, StitchCalib


def serial_from_filename(filename: str) -> str:
    stem = Path(filename).stem
    return stem


def parse_pts(path: Path) -> StitchCalib:
    text = path.read_text(encoding="utf-8-sig")
    if text.lstrip().startswith("#"):
        raise ValueError(
            "Legacy text PTO format detected. Re-save the project from PTGUI 11+ as JSON .pts."
        )
    data = json.loads(text)
    project = data.get("project")
    if not project:
        raise ValueError("Missing 'project' section in .pts file")

    calib = StitchCalib(source_pts=path.name)

    pano = project.get("panoramaparams", {})
    calib.pano = PanoParams(
        projection=pano.get("projection", "cylindrical"),
        hfov_deg=float(pano.get("hfov", 360.0)),
        vfov_deg=float(pano.get("vfov", 90.0)),
        crop=tuple(float(x) for x in pano.get("outputcrop", [0, 0, 1, 1])),
    )
    out_size = project.get("outputsize", {})
    calib.pano.size_fraction = float(out_size.get("fractionofoptimumsize", 1.0))

    global_lenses = project.get("globallenses", [])
    if global_lenses:
        lp = global_lenses[0].get("lens", {}).get("params", {})
        shift = global_lenses[0].get("shift", {}).get("params", {})
        calib.lens = LensParams(
            projection=lp.get("projection", "rectilinear"),
            focal_mm=float(lp.get("focallength", 3.0)),
            sensor_diagonal_mm=float(lp.get("sensordiagonal", 3.84)),
            a=float(lp.get("a", 0.0)),
            b=float(lp.get("b", 0.0)),
            c=float(lp.get("c", 0.0)),
            shift_long=float(shift.get("longside", 0.0)),
            shift_short=float(shift.get("shortside", 0.0)),
        )

    for gi, group in enumerate(project.get("imagegroups", [])):
        size = group.get("size", [256, 192])
        pos = group.get("position", {}).get("params", {})
        images = group.get("images", [])
        if not images:
            continue
        filename = images[0].get("filename", "")
        serial = serial_from_filename(filename)
        calib.cameras[serial] = CameraPose(
            serial=serial,
            width=int(size[0]),
            height=int(size[1]),
            yaw_deg=float(pos.get("yaw", 0.0)),
            pitch_deg=float(pos.get("pitch", 0.0)),
            roll_deg=float(pos.get("roll", 0.0)),
            ptgui_index=gi,
        )

    for cp in project.get("controlpoints", []):
        calib.control_points.append(cp)

    return calib


def calib_to_dict(calib: StitchCalib) -> dict:
    return {
        "version": calib.version,
        "source_pts": calib.source_pts,
        "lens": {
            "projection": calib.lens.projection,
            "focal_mm": calib.lens.focal_mm,
            "sensor_diagonal_mm": calib.lens.sensor_diagonal_mm,
            "a": calib.lens.a,
            "b": calib.lens.b,
            "c": calib.lens.c,
            "shift_long": calib.lens.shift_long,
            "shift_short": calib.lens.shift_short,
        },
        "pano": {
            "projection": calib.pano.projection,
            "hfov_deg": calib.pano.hfov_deg,
            "vfov_deg": calib.pano.vfov_deg,
            "crop": list(calib.pano.crop),
            "size_fraction": calib.pano.size_fraction,
        },
        "cameras": {
            serial: {
                "serial": cam.serial,
                "width": cam.width,
                "height": cam.height,
                "yaw_deg": cam.yaw_deg,
                "pitch_deg": cam.pitch_deg,
                "roll_deg": cam.roll_deg,
                "ptgui_index": cam.ptgui_index,
                "yaw_offset_deg": cam.yaw_offset_deg,
                "pitch_offset_deg": cam.pitch_offset_deg,
            }
            for serial, cam in sorted(calib.cameras.items())
        },
        "control_points": calib.control_points,
    }


def calib_from_dict(data: dict) -> StitchCalib:
    lens_d = data.get("lens", {})
    pano_d = data.get("pano", {})
    calib = StitchCalib(
        version=int(data.get("version", 1)),
        source_pts=data.get("source_pts", ""),
        lens=LensParams(
            projection=lens_d.get("projection", "rectilinear"),
            focal_mm=float(lens_d.get("focal_mm", 3.0)),
            sensor_diagonal_mm=float(lens_d.get("sensor_diagonal_mm", 3.84)),
            a=float(lens_d.get("a", 0.0)),
            b=float(lens_d.get("b", 0.0)),
            c=float(lens_d.get("c", 0.0)),
            shift_long=float(lens_d.get("shift_long", 0.0)),
            shift_short=float(lens_d.get("shift_short", 0.0)),
        ),
        pano=PanoParams(
            projection=pano_d.get("projection", "cylindrical"),
            hfov_deg=float(pano_d.get("hfov_deg", 360.0)),
            vfov_deg=float(pano_d.get("vfov_deg", 90.0)),
            crop=tuple(float(x) for x in pano_d.get("crop", [0, 0, 1, 1])),
            size_fraction=float(pano_d.get("size_fraction", 1.0)),
        ),
        control_points=list(data.get("control_points", [])),
    )
    for serial, cam_d in data.get("cameras", {}).items():
        calib.cameras[serial] = CameraPose(
            serial=serial,
            width=int(cam_d["width"]),
            height=int(cam_d["height"]),
            yaw_deg=float(cam_d["yaw_deg"]),
            pitch_deg=float(cam_d["pitch_deg"]),
            roll_deg=float(cam_d["roll_deg"]),
            ptgui_index=int(cam_d.get("ptgui_index", 0)),
            yaw_offset_deg=float(cam_d.get("yaw_offset_deg", 0.0)),
            pitch_offset_deg=float(cam_d.get("pitch_offset_deg", 0.0)),
        )
    calib.output_width = int(data.get("output_width", 0))
    calib.output_height = int(data.get("output_height", 0))
    return calib


def main() -> int:
    ap = argparse.ArgumentParser(description="Parse PTGUI .pts to stitch-calib.json")
    ap.add_argument("pts", type=Path, help="PTGUI project file (*.pts)")
    ap.add_argument("--out", type=Path, required=True, help="Output directory")
    args = ap.parse_args()

    calib = parse_pts(args.pts)
    args.out.mkdir(parents=True, exist_ok=True)
    out_path = args.out / "calib.json"
    payload = calib_to_dict(calib)
    out_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out_path} ({len(calib.cameras)} cameras, {len(calib.control_points)} control points)")
    for serial in sorted(calib.cameras):
        c = calib.cameras[serial]
        print(f"  {serial}: yaw={c.yaw_deg:.2f} pitch={c.pitch_deg:.2f} roll={c.roll_deg:.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
