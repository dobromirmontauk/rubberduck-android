#!/usr/bin/env python3
"""
Strip the flat chroma-key backdrop from raw generated pose images and write
true transparent (RGBA) PNGs.

Uses `rembg` (ML background removal) by default, which handles the soft,
anti-aliased edges of the clay-render style better than a naive color-key —
the clay is matte and slightly translucent at the silhouette edge, and a hard
color-key tends to leave a green fringe or bite into the silhouette. Falls
back to a chroma-key against #00B140 if rembg is unavailable or --chroma-key
is passed explicitly.

Setup:
    python3 -m venv .venv && source .venv/bin/activate
    pip install rembg pillow onnxruntime

Usage:
    python3 strip_bg.py --raw-dir ./raw --out-dir ../  [--chroma-key]
"""

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image

BACKDROP_RGB = (255, 255, 255)  # flat white studio backdrop used by generate_poses.py


def chroma_key(img: Image.Image, key=BACKDROP_RGB, tolerance=40) -> Image.Image:
    """Simple distance-based chroma key against the flat #00B140 backdrop."""
    img = img.convert("RGBA")
    pixels = img.load()
    w, h = img.size
    kr, kg, kb = key
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            dist = ((r - kr) ** 2 + (g - kg) ** 2 + (b - kb) ** 2) ** 0.5
            if dist < tolerance:
                pixels[x, y] = (r, g, b, 0)
    return img


def decontaminate_spill(img_rgba: Image.Image, bg=BACKDROP_RGB) -> Image.Image:
    """
    Remove green-backdrop spill from edge pixels using the standard matting
    decontamination formula: given a known background color B and an alpha
    matte, recover the true foreground color F from the composite C via
        F = (C - (1 - alpha) * B) / alpha
    This is exact (not a heuristic despill) because we know the exact flat
    backdrop color used during generation. Fully opaque interior pixels are
    unaffected (alpha=1 => F=C); only partially-transparent edge pixels get
    their green-screen contribution subtracted out.
    """
    arr = np.asarray(img_rgba, dtype=np.float64)
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3:4] / 255.0
    bg_arr = np.array(bg, dtype=np.float64).reshape(1, 1, 3)

    # Avoid dividing by ~0 alpha; those pixels are transparent anyway and
    # their color doesn't matter, so just leave them as-is.
    safe_alpha = np.clip(alpha, 0.08, 1.0)
    decontaminated = (rgb - (1 - safe_alpha) * bg_arr) / safe_alpha
    decontaminated = np.clip(decontaminated, 0, 255)

    # Only apply the correction where alpha < ~0.98 (edge pixels); leave
    # fully-opaque interior pixels exactly as rembg produced them.
    edge_mask = (alpha < 0.98)
    out_rgb = np.where(edge_mask, decontaminated, rgb)

    out = np.concatenate([out_rgb, arr[:, :, 3:4]], axis=2).astype(np.uint8)
    return Image.fromarray(out)


def strip_with_rembg(img: Image.Image) -> Image.Image:
    from rembg import remove  # imported lazily so chroma-key mode has no hard dep
    result = remove(img.convert("RGBA"))
    return decontaminate_spill(result)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--raw-dir", required=True, help="directory of raw green-backdrop PNGs")
    ap.add_argument("--out-dir", required=True, help="directory to write transparent PNGs")
    ap.add_argument("--chroma-key", action="store_true", help="force color-key mode instead of rembg")
    args = ap.parse_args()

    raw_dir = Path(args.raw_dir)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    use_rembg = not args.chroma_key
    if use_rembg:
        try:
            import rembg  # noqa: F401
        except ImportError:
            print("rembg not installed, falling back to chroma-key. "
                  "Run: pip install rembg onnxruntime", file=sys.stderr)
            use_rembg = False

    raws = sorted(raw_dir.glob("*.png"))
    if not raws:
        print(f"no PNGs found in {raw_dir}", file=sys.stderr)
        return 1

    for raw_path in raws:
        # Pillow sniffs actual bytes, so this loads correctly even if the
        # generator wrote JPEG bytes under a .png name.
        img = Image.open(raw_path)
        out_path = out_dir / raw_path.name

        if use_rembg:
            result = strip_with_rembg(img)
        else:
            result = chroma_key(img)

        result.save(out_path, format="PNG")
        print(f"{raw_path.name} -> {out_path} ({'rembg' if use_rembg else 'chroma-key'})")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
