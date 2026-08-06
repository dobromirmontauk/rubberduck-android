#!/usr/bin/env python3
"""
Verify every pose PNG in a directory is a real transparent RGBA image with
clean edges: correct file count, RGBA mode, transparent corners, and a
non-trivial amount of fully-opaque interior (i.e. it isn't accidentally
all-transparent or all-opaque).

Usage:
    python3 verify_alpha.py --dir ../ --expect-count 40
"""

import argparse
import sys
from pathlib import Path

from PIL import Image


def corner_alphas(img: Image.Image, margin: int = 2):
    w, h = img.size
    coords = [
        (margin, margin),
        (w - 1 - margin, margin),
        (margin, h - 1 - margin),
        (w - 1 - margin, h - 1 - margin),
    ]
    return [img.getpixel(c)[3] for c in coords]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", required=True, help="directory of pose PNGs to verify")
    ap.add_argument("--expect-count", type=int, default=None, help="fail if the PNG count doesn't match")
    ap.add_argument("--corner-alpha-max", type=int, default=10, help="max allowed alpha at corners (0=fully transparent)")
    args = ap.parse_args()

    d = Path(args.dir)
    files = sorted(d.glob("*.png"))

    if args.expect_count is not None and len(files) != args.expect_count:
        print(f"FAIL: expected {args.expect_count} files, found {len(files)}", file=sys.stderr)
        return 1

    failures = []
    for f in files:
        img = Image.open(f)
        if img.mode != "RGBA":
            img = img.convert("RGBA")
            reason = f"mode was {Image.open(f).mode}, not RGBA (converted has no real alpha data)"
            if Image.open(f).mode != "RGBA":
                failures.append((f.name, reason))
                continue

        alphas = corner_alphas(img)
        if any(a > args.corner_alpha_max for a in alphas):
            failures.append((f.name, f"corner alpha values {alphas} exceed max {args.corner_alpha_max}"))
            continue

        # Sanity check: there should be a meaningful opaque region (the character),
        # not an all-transparent or near-all-transparent image.
        w, h = img.size
        sample_step = max(1, min(w, h) // 40)
        opaque_pixels = 0
        total_sampled = 0
        for y in range(0, h, sample_step):
            for x in range(0, w, sample_step):
                total_sampled += 1
                if img.getpixel((x, y))[3] > 200:
                    opaque_pixels += 1
        opaque_frac = opaque_pixels / total_sampled if total_sampled else 0
        if opaque_frac < 0.05:
            failures.append((f.name, f"only {opaque_frac:.1%} of sampled pixels are opaque — likely stripped too aggressively"))
            continue

        print(f"OK   {f.name}  size={img.size}  corner_alphas={alphas}  opaque~{opaque_frac:.0%}")

    if failures:
        print(f"\n{len(failures)} file(s) failed verification:", file=sys.stderr)
        for name, reason in failures:
            print(f"  FAIL {name}: {reason}", file=sys.stderr)
        return 1

    print(f"\nAll {len(files)} files verified: true RGBA, transparent corners, non-trivial opaque region.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
