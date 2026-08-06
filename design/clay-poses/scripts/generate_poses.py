#!/usr/bin/env python3
"""
Generate the 40 raw (green-backdrop) clay-duck pose images for vn-edu.65.

This calls the nanobanana-image skill's `generate.py` (Gemini direct API) once
per pose in poses.py, passing the style-B reference sheet as --ref on every
call for character consistency, plus the shared character-lock + chroma-key
backdrop instruction + the pose-specific description as the prompt.

Usage:
    export GEMINI_API_KEY=...   # or GOOGLE_API_KEY, or a .env file the
                                 # generator can pick up
    python3 generate_poses.py \\
        --generator /path/to/nanobanana-image/generate.py \\
        --ref /path/to/design/duck-sheet-b-clay-3d.png \\
        --raw-dir ./raw \\
        [--only 05,06,07] [--aspect 1:1] [--model gemini-3-pro-image-preview]

Outputs land in --raw-dir named exactly as in poses.py (e.g.
01-idle-breathing-01.png). Run scripts/strip_bg.py next to convert these
green-backdrop raws into transparent PNGs, then scripts/verify_alpha.py to
confirm every file has a real alpha channel.

NOTE: the nanobanana generator sometimes writes JPEG bytes under a .png
filename. This script does not normalize that — strip_bg.py's Pillow-based
loader handles either, since Pillow sniffs actual file contents rather than
trusting the extension.
"""

import argparse
import subprocess
import sys
from pathlib import Path

from poses import POSES, CHARACTER_LOCK, BACKDROP_INSTRUCTION


def build_prompt(pose_prompt: str) -> str:
    return f"{CHARACTER_LOCK}\n\n{BACKDROP_INSTRUCTION}\n\nPOSE: {pose_prompt}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--generator", required=True, help="path to nanobanana-image generate.py")
    ap.add_argument("--ref", required=True, help="path to duck-sheet-b-clay-3d.png reference image")
    ap.add_argument("--raw-dir", required=True, help="output directory for raw green-backdrop PNGs")
    ap.add_argument("--python", default="/usr/bin/python3", help="python interpreter to run generate.py with")
    ap.add_argument("--model", default=None, help="override NB_MODEL / --model for generate.py")
    ap.add_argument("--aspect", default="1:1", help="aspect ratio passed to generate.py")
    ap.add_argument("--only", default=None, help="comma-separated 2-digit indices to (re)generate, e.g. 05,06,40")
    ap.add_argument("--dry-run", action="store_true", help="print commands without calling the API")
    args = ap.parse_args()

    raw_dir = Path(args.raw_dir)
    raw_dir.mkdir(parents=True, exist_ok=True)

    only = None
    if args.only:
        only = {s.strip() for s in args.only.split(",") if s.strip()}

    failures = []
    for filename, sequence, pose_prompt in POSES:
        idx = filename.split("-", 1)[0]
        if only is not None and idx not in only:
            continue

        out_path = raw_dir / filename
        prompt = build_prompt(pose_prompt)
        cmd = [
            args.python, args.generator,
            "--prompt", prompt,
            "--out", str(out_path),
            "--ref", args.ref,
            "--aspect", args.aspect,
        ]
        if args.model:
            cmd += ["--model", args.model]

        print(f"== [{sequence}] {filename} ==")
        if args.dry_run:
            print("  (dry run) " + " ".join(f'"{c}"' if " " in c else c for c in cmd))
            continue

        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"  FAILED: {result.stderr.strip()[-2000:]}", file=sys.stderr)
            failures.append(filename)
        else:
            print(f"  ok -> {out_path}")

    if failures:
        print(f"\n{len(failures)} pose(s) failed: {', '.join(failures)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
