#!/usr/bin/env python3
"""
Compose one nav-icon vector (traced duck body + a badge_shapes accent) as
both a preview SVG (for rsvg-convert eyeballing) and the final Android
VectorDrawable XML, from a small per-icon spec below. See badge_shapes.py
for the accent path generators and design/icon-assets/README.md for the
overall pipeline this plugs into.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from badge_shapes import bars_path, gear_ring_path, door_arrow_path  # noqa: E402

TRACED_DIR = Path(__file__).resolve().parent / "traced"


def load_duck_group(svg_name: str):
    text = (TRACED_DIR / svg_name).read_text()
    vb = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', text)
    tf = re.search(r'<g transform="translate\(([\-\d.]+),([\-\d.]+)\) scale\(([\-\d.]+),([\-\d.]+)\)"', text)
    d = re.search(r'<path d="([^"]+)"', text, re.DOTALL)
    vb_w, vb_h = float(vb.group(1)), float(vb.group(2))
    tx, ty, sx, sy = (float(g) for g in tf.groups())
    path_d = " ".join(d.group(1).split())
    return vb_w, vb_h, tx, ty, sx, sy, path_d


# Badge box: top-right corner free zone in the 285x285 listen.svg viewport
# (duck alpha bbox is ~17..268 x 20..264 -- see badge_shapes.py docstring).
BADGE_X, BADGE_Y, BADGE_W, BADGE_H = 208, 4, 72, 60

ICONS = {
    "ic_nav_sessions": {
        "badge": lambda: bars_path(
            x=BADGE_X + 6, y=BADGE_Y + 8, width=BADGE_W - 12, bar_height=12, gap=6, count=3,
        ),
    },
    "ic_nav_settings": {
        "badge": lambda: gear_ring_path(
            cx=BADGE_X + BADGE_W / 2, cy=BADGE_Y + BADGE_H / 2, r_outer=22, r_inner=9, teeth=8, tooth_depth=6,
        ),
    },
    "ic_nav_signin": {
        "badge": lambda: door_arrow_path(x=BADGE_X, y=BADGE_Y + 6, w=BADGE_W, h=BADGE_H - 12, pointing_right=True),
    },
    "ic_nav_signout": {
        "badge": lambda: door_arrow_path(x=BADGE_X, y=BADGE_Y + 6, w=BADGE_W, h=BADGE_H - 12, pointing_right=False),
    },
}


def build(name: str, spec: dict, out_dir_svg: Path, out_dir_xml: Path):
    vb_w, vb_h, tx, ty, sx, sy, duck_d = load_duck_group("listen.svg")
    badge_d = spec["badge"]()

    svg = f"""<?xml version="1.0" standalone="no"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{vb_w:.0f}" height="{vb_h:.0f}" viewBox="0 0 {vb_w:.0f} {vb_h:.0f}">
<g transform="translate({tx},{ty}) scale({sx},{sy})" fill="#000000"><path d="{duck_d}"/></g>
<path d="{badge_d}" fill="#000000"/>
</svg>
"""
    (out_dir_svg / f"{name}.svg").write_text(svg)

    xml = f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{vb_w:.0f}"
    android:viewportHeight="{vb_h:.0f}">
    <group
        android:scaleX="{sx}"
        android:scaleY="{sy}"
        android:translateX="{tx}"
        android:translateY="{ty}">
        <path
            android:fillColor="#FF000000"
            android:pathData="{duck_d}" />
    </group>
    <path
        android:fillColor="#FF000000"
        android:pathData="{badge_d}" />
</vector>
"""
    (out_dir_xml / f"{name}.xml").write_text(xml)
    print(f"wrote {name}.svg / {name}.xml (viewport {vb_w:.0f}x{vb_h:.0f})")


if __name__ == "__main__":
    svg_dir = Path(__file__).resolve().parent / "traced" / "nav"
    xml_dir = Path(__file__).resolve().parent / "traced" / "nav"
    svg_dir.mkdir(parents=True, exist_ok=True)
    for name, spec in ICONS.items():
        build(name, spec, svg_dir, xml_dir)
