# Nav-icon prop renders (vn-edu.73 v2)

v1 of vn-edu.73 (see `design/icon-assets/README.md`'s nav-icon section) built
all four non-mic nav icons from one shared duck-body silhouette plus a
generic accent badge (a gear, a stack of bars, a door+arrow). **User verdict:
rejected** -- "identical icon with a gear box." The ask instead: each nav
icon is a distinct yellow duck POSE holding/wearing a literal, on-model prop,
clay-style like the rest of the app's mascot art, in full color (not a
tinted silhouette).

## Pipeline

1. `props.py` — one pose-specific prompt per nav item, sharing the same
   `CHARACTER_LOCK`/`BACKDROP_INSTRUCTION` convention as
   `design/clay-poses/scripts/poses.py`, plus a `PROP_INSTRUCTION` that
   forces every prop to be physically HELD or WORN (never floating
   detached) -- floating props don't matte cleanly with `rembg` (see
   `design/clay-poses/MANIFEST.md`'s note on the dropped BRB-sign prop).
2. `generate.py` calls the nanobanana direct API once per pose (mirrors
   `design/clay-poses/scripts/generate_poses.py`), passing
   `design/duck-sheet-b-clay-3d.png` as `--ref` for character consistency.
   Output: `raw/{mic,scroll,hammer,door_in,door_out}.png` (1024x1024, white
   backdrop). All 5 landed on the first generation pass, no regenerations
   needed.
3. `design/clay-poses/scripts/strip_bg.py` (rembg + matting decontamination,
   same script the mascot library uses; needs a venv with `rembg pillow
   onnxruntime numpy` -- not committed here) removes the backdrop ->
   `stripped/*.png`. `verify_alpha.py` confirms true RGBA + transparent
   corners + non-trivial opaque interior on all 5.
4. A one-off normalize step (alpha-bbox crop, scale to a shared 168px
   height, bottom-anchor on a 192x192 canvas, same idea as
   `normalize_poses.py` but sized for a nav icon rather than the 512px
   mascot frames) -> `final/*.png`. These are copied byte-for-byte into
   `app/src/main/res/drawable-nodpi/ic_nav_{new_session,sessions,settings,
   signin,signout}.png`.

## What's what

| nav item | prop | final asset | shipped as |
|---|---|---|---|
| New Session | microphone at the beak | `final/mic.png` | `ic_nav_new_session.png` |
| Sessions | unrolled paper scroll | `final/scroll.png` | `ic_nav_sessions.png` |
| Settings | hammer + tool belt | `final/hammer.png` | `ic_nav_settings.png` |
| Sign In | stepping through a doorway, waving hello | `final/door_in.png` | `ic_nav_signin.png` |
| Log Out | walking away through a doorway, waving goodbye | `final/door_out.png` | `ic_nav_signout.png` |

`preview/nav_set_192_strip.png` is all 5 side by side at shipped resolution;
`preview/nav_set_48_preview.png` is a nearest-neighbor-upscaled 48px gut
check (pessimistic vs. real bilinear downsampling in Compose, but useful to
catch a prop that vanishes entirely at nav-bar size -- none did).
`preview/*_on_green.png` are the individual rembg-matting QA renders (green
backdrop makes any white-fringe/matting failure obvious; none showed one
except a faint interior smudge inside `door_in`'s doorframe opening, invisible
at shipped size).

These are full-color raster assets, not vectors -- `BottomNavBar.kt` uses
`Icon(..., tint = Color.Unspecified)` so Compose doesn't flatten them to a
single-tint silhouette the way it would a Material glyph.
