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
- `traced/converse.svg` is the open-beak + sound-lines CONVERSE pose, used as
  the "New Session" bottom-nav glyph (vn-edu.73) — already reads as "duck
  speaking" with no further editing needed.
- `traced/challenge.svg` (skeptical/crossed-wings pose) is not yet used by any
  landed icon; kept here as reusable raw material.
- `traced/listen.svg` (small ~6% margin variant, distinct from
  `listen_launcher_mono.svg`'s ~22% margin) is the calm resting-duck body
  shared across the Sessions/Settings/Sign-in/Log-out nav icons.

## Nav-icon badges (vn-edu.73's remaining 4 glyphs)

`badge_shapes.py` hand-generates small accent-path `d` strings (a 3-bar notes
stack, a gear ring via nonzero-winding annulus, a door+arrow login/logout
glyph) in the same 0..285 coordinate space as `traced/listen.svg`'s duck
body, since the duck's own alpha bbox (~17..268 x 20..264) leaves a small
free corner at roughly x:[208,280] y:[4,64]. `build_nav_icon.py` composites
`traced/listen.svg`'s duck-body group with one badge path per icon and
writes both a preview SVG and the final Android XML to `traced/nav/`; those
XML files are then copied byte-for-byte into
`app/src/main/res/drawable/ic_nav_{sessions,settings,signin,signout}.xml`
(see each file's header comment for which badge it uses and why -- the gear
and door+arrow are legibility-driven substitutions for the bead's literal
"wrench"/"waving duck" asks, flagged there for the lead's visual review
gate). `ic_nav_new_session.xml` skips the badge step entirely and just
ports `traced/converse.svg` directly (its open-beak + sound-lines already
read as "duck speaking").

Regenerate with:

```
python3 svg_trace_to_avd.py traced/<name>.svg /path/to/out.xml --size 24dp
python3 build_nav_icon.py   # rebuilds traced/nav/ic_nav_{sessions,settings,signin,signout}.{svg,xml}
```
