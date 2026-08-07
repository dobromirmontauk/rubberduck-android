# Style-A flat-vector icon pipeline

Source material for beads vn-edu.63 (launcher icon) and vn-edu.73 (duck-themed
UI icons): isolated, single-pose crops from
`design/duck-sheet-a-flat-vector.png` (the LISTEN / CONVERSE / CHALLENGE
3-pose sheet), plus vector traces of each for use as VectorDrawable path data.

## Pipeline

1. **Isolate** — the sheet's flat navy background (`#282f38`-ish, sampled as
   `(40, 49, 56)`) is removed with a smooth color-distance chroma key (not
   `rembg` — this art style has crisp vector-like edges, so a plain distance
   threshold with a soft ramp between `tol_low=18` and `tol_high=55` mattes
   cleanly with no fringing). Each of the 3 poses is cropped to its cell,
   keyed, and cropped again to its alpha bbox (excluding the sheet's caption
   text) -> `raw/{listen,converse,challenge}_isolated.png`.
2. **Pad to a square canvas** — `raw/{name}_square.png` for general reuse
   (small ~6% margin), or `raw/listen_launcher_fg.png` for the launcher
   specifically (larger ~22% margin so the duck stays inside the
   adaptive-icon safe zone across circle/squircle/rounded-square masks —
   Android's masks clip outside ~61% of the 108dp canvas).
3. **Binarize + potrace** — alpha-threshold each square PNG into a `.pbm`
   mask, then `potrace --svg --turdsize 4 --opttolerance 0.4` traces it into
   a single vector `<path>` (nonzero winding rule; internal details like the
   eye and wing line survive as holes via opposing-direction subpaths) ->
   `traced/{name}.svg` / `traced/listen_launcher_mono.svg`.
4. **Port to Android** — `svg_trace_to_avd.py` reads potrace's
   `<g transform="translate(...) scale(...)">`-wrapped path and emits an
   Android `<vector>` with an equivalent `<group android:scaleX/Y
   android:translateX/Y>` wrapping the same `pathData` string unchanged (AVD's
   path grammar is the same M/L/C/Z-with-implicit-repetition grammar SVG
   uses, and AVD's group transform composes scale-then-translate around
   pivot (0,0) the same way the SVG transform list does, so no coordinate
   rewriting is needed — see the script's docstring).
5. **Preview** — `preview/*_preview.png` renders (via `rsvg-convert`,
   composited onto light gray) let you eyeball a trace before it goes into
   the app; they are not shipped, just checked-in review artifacts.

## What's landed vs. what's raw material here

- `ic_launcher_foreground.png` (from `raw/listen_launcher_fg.png`) and
  `ic_launcher_monochrome.xml` (from `traced/listen_launcher_mono.svg`) are
  the actual shipped launcher-icon assets (vn-edu.63) — see
  `app/src/main/res/drawable{-nodpi,}/ic_launcher_*`.
- `traced/converse.svg` (open-beak + sound-lines CONVERSE pose) and
  `traced/challenge.svg` (skeptical/crossed-wings pose) are not used by any
  landed icon; kept here as reusable raw material.
- `traced/listen.svg` (small ~6% margin variant, distinct from
  `listen_launcher_mono.svg`'s ~22% margin) is not currently used by any
  landed icon either; kept as reusable raw material.

## Nav-icon badges — REJECTED, superseded by design/nav-icon-props/

The first pass at vn-edu.73's remaining 4 nav glyphs built them from
`traced/listen.svg`'s duck-body silhouette plus a small hand-authored accent
badge (`badge_shapes.py`: a 3-bar notes stack, a gear ring, a door+arrow),
composited by `build_nav_icon.py` into `traced/nav/ic_nav_{sessions,
settings,signin,signout}.{svg,xml}`. **User verdict: rejected** — "identical
icon with a gear box." `badge_shapes.py`/`build_nav_icon.py`/`traced/nav/`
are left in place as a record of what was tried and why it didn't land (same
one-fillColor-tinted-silhouette limitation this whole style-A pipeline was
built around), but none of their output is wired into the app anymore — see
`design/nav-icon-props/README.md` for the v2 approach that replaced it
(distinct full-color clay-render duck poses holding literal props, generated
via nanobanana rather than traced from the flat-vector sheet).
