#!/usr/bin/env python3
"""
Convert a potrace --svg output (one <g transform="translate(...) scale(...)">
wrapping a single <path d="...">, as produced by this repo's icon pipeline)
into an Android VectorDrawable XML fragment.

Potrace emits path coordinates in its own internal space (10x scale, Y-up)
and corrects for it with a <g transform="translate(0,H) scale(0.1,-0.1)">
around the path. Android's <group> element applies scale then translate to
its children in the same composition order as SVG's transform list here
(scale first, then translate, both around origin since no pivot is set), so
the transform can be carried over as android:scaleX/Y + translateX/Y on a
wrapping <group> with no change to the path data itself -- the path's
command grammar (M/L/C/Z, relative lowercase, implicit command repetition)
is the same grammar AVD's PathParser accepts.

Usage:
    python3 svg_trace_to_avd.py <in.svg> <out.xml> --size 24dp [--fill "#FF000000"]
"""

import argparse
import re
import sys


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("svg_in")
    ap.add_argument("xml_out")
    ap.add_argument("--size", default="24dp", help="android:width/height for the vector (default 24dp)")
    ap.add_argument("--fill", default="#FF000000", help="android:fillColor for the traced path")
    args = ap.parse_args()

    text = open(args.svg_in).read()

    vb_match = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', text)
    if not vb_match:
        print("FAIL: no viewBox found", file=sys.stderr)
        return 1
    vb_w, vb_h = float(vb_match.group(1)), float(vb_match.group(2))

    tf_match = re.search(
        r'<g transform="translate\(([\-\d.]+),([\-\d.]+)\) scale\(([\-\d.]+),([\-\d.]+)\)"',
        text,
    )
    if not tf_match:
        print("FAIL: no g-transform found", file=sys.stderr)
        return 1
    tx, ty, sx, sy = (float(g) for g in tf_match.groups())

    path_match = re.search(r'<path d="([^"]+)"', text, re.DOTALL)
    if not path_match:
        print("FAIL: no path found", file=sys.stderr)
        return 1
    d = " ".join(path_match.group(1).split())  # collapse potrace's line-wrapped whitespace

    viewport_w = round(vb_w)
    viewport_h = round(vb_h)

    xml = f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{args.size}"
    android:height="{args.size}"
    android:viewportWidth="{viewport_w}"
    android:viewportHeight="{viewport_h}">
    <group
        android:scaleX="{sx}"
        android:scaleY="{sy}"
        android:translateX="{tx}"
        android:translateY="{ty}">
        <path
            android:fillColor="{args.fill}"
            android:pathData="{d}" />
    </group>
</vector>
"""
    with open(args.xml_out, "w") as f:
        f.write(xml)
    print(f"wrote {args.xml_out} (viewport {viewport_w}x{viewport_h}, transform scale={sx},{sy} translate={tx},{ty})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
