# Clay Duck Animation Pose Library — Manifest

40 poses of the rubberduck mascot in the chosen "Style B" soft 3D / clay-render
look (`design/duck-sheet-b-clay-3d.png`), organized as animation SEQUENCES
rather than 40 unrelated poses. Each sequence is a short loop or transition
meant to be played back frame-by-frame in the app; deltas between consecutive
frames within a sequence are intentionally SMALL (limb/eye/wing motion only —
no camera changes, no palette changes, no proportion changes).

## Shared character lock (repeat in every generation prompt)

> A single warm-yellow soft-clay-render duck character, claymation style: smooth
> rounded matte clay body, visible tiny clay tool-dimples, soft even studio
> lighting, gentle ambient occlusion, coral-orange clay beak, coral-orange
> scarf/accent patch on the chest. Round body, small stubby wings, simple dot
> eyes. Same proportions and colors as the reference image — do not change body
> shape, palette, or accessory. Default camera: waist-up, 3/4-front angle,
> character centered and fully in frame, facing slightly toward camera-left,
> unless the pose note below says otherwise.

Reference image passed on every generation call: `design/duck-sheet-b-clay-3d.png`
(rubberduck-android repo).

## Backdrop / transparency convention

Every pose is generated on a **flat, uniform chroma-key green background**
(`#00B140`, a color that never appears on the character) with soft neutral
studio shadow only directly beneath the character's feet — no props, no floor
line, no gradient. The background is stripped to alpha in a second pass (see
`scripts/strip_bg.py`) and every output file is verified to be true RGBA with
transparent corner pixels before landing.

## File naming

`NN-<sequence-slug>-<frame>.png` — `NN` is the running index (01-40, matches
generation order); `<frame>` is zero-padded within its sequence.

## Sequences

### 1. Idle-breathing cycle (4 frames) — loop
Resting/default state. A slow, subtle breathing loop for when the duck is on
screen but not actively doing anything.

- `01-idle-breathing-01.png` — neutral resting pose, chest at rest, eyes soft and half-lidded, calm.
- `02-idle-breathing-02.png` — chest very slightly raised (early inhale), otherwise identical to frame 1.
- `03-idle-breathing-03.png` — chest at its highest point (peak inhale), shoulders/wings just barely lifted.
- `04-idle-breathing-04.png` — chest lowering back toward neutral (exhale), between frame 3 and frame 1 — loops back to `01`.

### 2. Blink cycle (3 frames) — loop, layers over idle
- `05-blink-01.png` — eyes fully open, same base pose as idle frame 1.
- `06-blink-02.png` — eyes half-closed, mid-blink, everything else unchanged.
- `07-blink-03.png` — eyes fully closed (a single held closed-eye frame), everything else unchanged.

### 3. Listening intro (3 frames) — one-shot, plays on mic-start
Transition into the "actively listening/recording" state.

- `08-listening-intro-01.png` — head begins to tilt/perk up slightly from neutral, eyes widening a touch.
- `09-listening-intro-02.png` — head tilted further, one wing lifting toward the side of the head.
- `10-listening-intro-03.png` — fully alert: head tilted, wing resting near the ear, eyes wide and attentive, two small sound-wave arcs beside the head (the settled "listening" pose).

### 4. Wing-flap cycle (4 frames) — loop
A cheerful flap, usable for transitions, loading, or excitement beats.

- `11-wing-flap-01.png` — both wings resting down at the sides (start/end of loop).
- `12-wing-flap-02.png` — wings lifting outward, roughly halfway raised.
- `13-wing-flap-03.png` — wings fully raised and spread, peak of the flap.
- `14-wing-flap-04.png` — wings lowering back down, roughly halfway — loops back to `11`.

### 5. Head turn, left → center → right (4 frames) — one-shot sweep
Same body, only the head/gaze direction changes; useful for "looking around" or glancing at a UI element.

- `15-head-turn-01.png` — head turned to look left (camera-right side of the frame).
- `16-head-turn-02.png` — head partway back toward center, still slightly left.
- `17-head-turn-03.png` — head facing forward, straight at camera (center).
- `18-head-turn-04.png` — head turned to look right, mirroring frame 1's angle to the other side.

### 6. Talk / beak visemes (4 frames) — loop, drives mouth during TTS/playback
Minimal, generic mouth shapes — not phoneme-accurate, just enough beak
open/close variety to look like natural talking when cycled quickly.

- `19-talk-viseme-01.png` — beak fully closed, resting/silent shape.
- `20-talk-viseme-02.png` — beak slightly open, small gap (soft consonant shape).
- `21-talk-viseme-03.png` — beak wide open (open vowel shape), the widest mouth frame in the set.
- `22-talk-viseme-04.png` — beak at a medium-open position, between frames 3 and 1 — loops back to `19`.

### 7. Thinking (3 frames) — one-shot, plays during "processing"
- `23-thinking-01.png` — duck looks up and to the side, one wing-tip just starting to lift toward the beak.
- `24-thinking-02.png` — wing-tip resting against the beak, head tilted further, a small thought-swirl beginning to appear above the head.
- `25-thinking-03.png` — deep-in-thought hold: eyes narrowed slightly, wing-tip still at beak, thought-swirl fully formed and slightly larger above the head.

### 8. Happy bounce (3 frames) — one-shot, plays on task complete
A squash-stretch bounce of joy.

- `26-happy-bounce-01.png` — anticipation/squash: knees-equivalent (body) compressed slightly downward, beak starting to open in a smile.
- `27-happy-bounce-02.png` — apex of the bounce: body stretched slightly taller/airborne-feeling, beak wide open in a big cheerful smile, eyes closed happily, small sparkle accents around the head.
- `28-happy-bounce-03.png` — landing/settle: body back to normal height, smile softening slightly, sparkles fading — settles into a contented rest.

### 9. Waddle cycle (4 frames) — loop, drives a walking/waddling animation
- `29-waddle-01.png` — weight shifted onto the left foot, body leaning slightly left, right foot lifted slightly.
- `30-waddle-02.png` — mid-step: feet roughly together beneath the body, upright, transitional pose.
- `31-waddle-03.png` — weight shifted onto the right foot, body leaning slightly right, left foot lifted slightly (mirror of frame 1).
- `32-waddle-04.png` — mid-step return: feet roughly together beneath the body, upright, transitional pose (mirror of frame 2) — loops back to `29`.

### 10. Wave (3 frames) — one-shot greeting
- `33-wave-01.png` — one wing raised up beside the head, starting the wave, beak in a small smile.
- `34-wave-02.png` — the raised wing tipped over to one side, mid-wave motion.
- `35-wave-03.png` — the raised wing tipped to the opposite side, completing the wave motion, big warm smile.

### 11. Alert / surprised (2 frames) — one-shot, sudden attention grab
- `36-alert-01.png` — eyes suddenly wide, body slightly tensed/pulled back, an initial startle.
- `37-alert-02.png` — held alert stance: eyes wide, wings slightly spread out from the body, eyebrows-equivalent raised, fully alert.

### 12. Sleepy (2 frames) — one-shot / idle-timeout state
- `38-sleepy-01.png` — eyelids drooping, head starting to lower, sluggish posture.
- `39-sleepy-02.png` — eyes nearly closed, head resting low and tilted, a small "z" doze accent near the head.

### 13. Challenge / devil's-advocate (1 frame) — mode-switcher state (feeds vn-edu.64)
- `40-challenge-01.png` — skeptical pose distinct from all above: one eyebrow-equivalent line raised, eyes narrowed slightly, both wings crossed in front of the chest, beak in a flat/pursed skeptical line — the "playing devil's advocate" stance for Challenge mode.

## Generation + verification notes

- Every call passes `design/duck-sheet-b-clay-3d.png` as `--ref` plus the shared
  character-lock text above, so the character stays on-model across all 40 files.
- Frame-to-frame deltas are deliberately described as small motion increments
  within each sequence (not scene changes), per the animation-frame requirement.
- After generation, `scripts/strip_bg.py` removes the `#00B140` backdrop and
  writes true RGBA PNGs; `scripts/verify_alpha.py` checks every file has mode
  `RGBA` and fully transparent corner pixels before landing.
- If a frame drifts off-model (wrong palette, wrong proportions, extra
  limbs/props), it is regenerated individually rather than accepted — this is
  logged in the commit message / report, not silently shipped.
