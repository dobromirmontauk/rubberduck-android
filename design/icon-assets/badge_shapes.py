#!/usr/bin/env python3
"""
Hand-authored accent-badge path generators for bead vn-edu.73's nav icons.

Each nav icon composites the traced calm-duck silhouette (design/icon-assets/
traced/listen.svg, viewport 0..285) with a small accessory badge in the empty
top-right corner of that viewport (the duck's own alpha bbox is roughly
x:[17,268] y:[20,264], leaving a ~65x60 corner free at x:[210,283] y:[2,62]).
All shapes are emitted as plain SVG/AVD path `d` strings in that same 0..285
coordinate space -- no separate transform needed, they sit in the same
<vector> as the duck group, as a sibling <path>.
"""

import math


def circle_path(cx: float, cy: float, r: float) -> str:
    """A full circle as two arcs (SVG/AVD arc command, works in both)."""
    return f"M{cx - r},{cy} A{r},{r} 0 1,0 {cx + r},{cy} A{r},{r} 0 1,0 {cx - r},{cy} Z"


def gear_ring_path(cx: float, cy: float, r_outer: float, r_inner: float, teeth: int = 8, tooth_depth: float = 6) -> str:
    """
    A simple gear silhouette: an outer scalloped ring (teeth as small radial
    bumps) around a hollow center, built as one nonzero-winding path -- an
    outer tooothed polygon followed by a reverse-wound inner circle to punch
    the hole (nonzero fill rule treats the opposite winding direction as a
    hole, same trick potrace's own output uses for the duck's eye/wing-line
    details).
    """
    pts = []
    n = teeth * 2
    for i in range(n):
        angle = 2 * math.pi * i / n
        r = r_outer + (tooth_depth if i % 2 == 0 else 0)
        pts.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
    outer = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"

    # Inner hole, wound opposite direction (clockwise vs the outer polygon's
    # counutrise-clockwise-in-screen-space winding) so nonzero fill punches it out.
    inner_pts = []
    steps = 24
    for i in range(steps):
        angle = -2 * math.pi * i / steps
        inner_pts.append((cx + r_inner * math.cos(angle), cy + r_inner * math.sin(angle)))
    inner = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in inner_pts) + " Z"

    return outer + " " + inner


def bars_path(x: float, y: float, width: float, bar_height: float, gap: float, count: int = 3) -> str:
    """`count` stacked horizontal rounded bars (a mini notes/list stack), top-left corner at (x, y)."""
    parts = []
    for i in range(count):
        by = y + i * (bar_height + gap)
        parts.append(f"M{x},{by} L{x + width},{by} L{x + width},{by + bar_height} L{x},{by + bar_height} Z")
    return " ".join(parts)


def door_arrow_path(x: float, y: float, w: float, h: float, pointing_right: bool) -> str:
    """
    A minimal login/logout glyph: a doorframe (open rectangle, right side
    open) plus an arrow through the opening -- pointing_right=True reads as
    "sign in" (arrow going into the frame), False as "log out" (arrow
    leaving it). Mirrors the semantics of Icons.AutoMirrored.Filled.Login/
    Logout, duck-themed by pairing with the duck badge rather than replacing
    it.
    """
    frame_w = w * 0.45
    # Doorframe: left wall + top + bottom, open on the right (a "[" shape).
    t = h * 0.16  # wall thickness
    frame = (
        f"M{x},{y} L{x + frame_w},{y} L{x + frame_w},{y + t} L{x + t},{y + t} "
        f"L{x + t},{y + h - t} L{x + frame_w},{y + h - t} L{x + frame_w},{y + h} L{x},{y + h} Z"
    )
    ay = y + h / 2
    shaft_x0, shaft_x1 = x + frame_w * 0.7, x + w * 0.92
    head = w * 0.22
    ah = h * 0.32
    if pointing_right:
        arrow = (
            f"M{shaft_x0},{ay - t * 0.55} L{shaft_x1 - head},{ay - t * 0.55} "
            f"L{shaft_x1 - head},{ay - ah} L{shaft_x1},{ay} L{shaft_x1 - head},{ay + ah} "
            f"L{shaft_x1 - head},{ay + t * 0.55} L{shaft_x0},{ay + t * 0.55} Z"
        )
    else:
        arrow = (
            f"M{shaft_x1},{ay - t * 0.55} L{shaft_x0 + head},{ay - t * 0.55} "
            f"L{shaft_x0 + head},{ay - ah} L{shaft_x0},{ay} L{shaft_x0 + head},{ay + ah} "
            f"L{shaft_x0 + head},{ay + t * 0.55} L{shaft_x1},{ay + t * 0.55} Z"
        )
    return frame + " " + arrow
