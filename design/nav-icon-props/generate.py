#!/usr/bin/env python3
"""
Generate the 5 raw (white-backdrop) nav-icon prop renders for vn-edu.73 v2.
See props.py for the pose list; mirrors design/clay-poses/scripts/
generate_poses.py's pattern (direct nanobanana API, duck-sheet-b-clay-3d.png
as --ref for character consistency).

Usage:
    export GEMINI_API_KEY=...
    python3 generate.py --generator /path/to/nanobanana-image/generate.py \\
        --ref /path/to/design/duck-sheet-b-clay-3d.png --raw-dir ./raw
"""

import argparse
import subprocess
import sys
from pathlib import Path

from props import PROPS, CHARACTER_LOCK, BACKDROP_INSTRUCTION, PROP_INSTRUCTION


def build_prompt(pose_prompt: str) -> str:
    return f"{CHARACTER_LOCK}\n\n{BACKDROP_INSTRUCTION}\n\n{PROP_INSTRUCTION}\n\nPOSE: {pose_prompt}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--generator", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--raw-dir", required=True)
    ap.add_argument("--python", default="/usr/bin/python3")
    ap.add_argument("--aspect", default="1:1")
    ap.add_argument("--only", default=None, help="comma-separated filenames to (re)generate")
    args = ap.parse_args()

    raw_dir = Path(args.raw_dir)
    raw_dir.mkdir(parents=True, exist_ok=True)

    only = {s.strip() for s in args.only.split(",")} if args.only else None

    failures = []
    for filename, nav_item, pose_prompt in PROPS:
        if only is not None and filename not in only:
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
        print(f"== [{nav_item}] {filename} ==")
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"  FAILED: {result.stderr.strip()[-2000:]}", file=sys.stderr)
            failures.append(filename)
        else:
            print(f"  ok -> {out_path}")

    if failures:
        print(f"\n{len(failures)} failed: {', '.join(failures)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
