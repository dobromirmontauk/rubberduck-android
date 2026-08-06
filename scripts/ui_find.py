#!/usr/bin/env python3
"""Finds a node in a `uiautomator dump` XML by its exact visible text or
content-description and prints its bounds' center as "X Y" to stdout.

Used by scripts/emulator-smoke.sh to drive the debug fixture-picker UI
(long-press "New Session" -> pick a fixture -> tap STOP). RecordingService
is `android:exported="false"`, so it can't be started directly via `adb
shell am start-foreground-service` from outside the app's own UID (confirmed
empirically: "Permission Denial: ... not exported from uid ..."); the only
externally-driveable path is the same long-press fixture picker a human uses
in Android Studio, so this harness drives that UI instead.

Usage: ui_find.py <dump.xml> <exact text or content-desc>
Exit 0 with "X Y" on stdout for the first matching node (document order);
exit 1 with a message on stderr and nothing on stdout if no node matches.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET


def find_center(path: str, target: str) -> tuple[int, int] | None:
    tree = ET.parse(path)
    for node in tree.iter("node"):
        if node.get("text", "") == target or node.get("content-desc", "") == target:
            bounds = node.get("bounds", "")
            # bounds format: "[x1,y1][x2,y2]"
            try:
                lhs, rhs = bounds.split("][")
                x1, y1 = lhs.lstrip("[").split(",")
                x2, y2 = rhs.rstrip("]").split(",")
            except ValueError:
                continue
            return (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2
    return None


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: ui_find.py <dump.xml> <text>", file=sys.stderr)
        return 2
    path, target = argv[1], argv[2]
    center = find_center(path, target)
    if center is None:
        print(f"no node with text/content-desc == {target!r} found in {path}", file=sys.stderr)
        return 1
    print(f"{center[0]} {center[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
