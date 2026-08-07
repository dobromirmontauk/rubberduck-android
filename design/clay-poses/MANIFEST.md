# Clay Duck Animation Pose Library — Manifest

49 poses of the rubberduck mascot in the chosen "Style B" soft 3D / clay-render
look (`design/duck-sheet-b-clay-3d.png`), organized as animation SEQUENCES
rather than unrelated poses. Each sequence is a short loop or transition
meant to be played back frame-by-frame in the app; deltas between consecutive
frames within a sequence are intentionally SMALL (limb/eye/wing motion only —
no camera changes, no palette changes, no proportion changes).

Poses 01-40 were generated for vn-edu.65 (see below); poses 41-49 were added
for asn-ek1 to fill gaps identified on the behavior-map design board: writing
on a notepad, cupping an ear to indicate "didn't catch that," an eager
one-wing-raised "hand raise" pose, and a fully-asleep pause-state pose. An
initial standalone BRB sign prop (41-46 numbering at the time) was generated
and briefly landed on main, then dropped after a design-board revision
removed it from scope — see the notes-scribble/ear-cup/hand-raise-eager/
sleeping sequences below for what actually shipped.

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

Every pose is generated on a **flat, uniform pure white background**
(`#FFFFFF`) with soft neutral studio shadow only directly beneath the
character's feet — no props, no floor line, no gradient. White was chosen
over a green chroma-key backdrop because green bounce light visibly bakes
into the matte clay surface; white does not tint the character. The
background is removed in a second pass using `rembg` plus matting-formula
spill decontamination against the known white backdrop (see
`scripts/strip_bg.py`), and every output file is verified to be true RGBA
with transparent corner pixels before landing (`scripts/verify_alpha.py`).

## File naming

`NN-<sequence-slug>-<frame>.png` — `NN` is the running index (01-49, matches
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

### 14. Notes scribble (3 frames) — loop, plays while jotting down a note
Duck holding a small spiral notepad against its chest in one wing, writing on
it with a short pencil held in the other wing. Distinct from the thinking
sequence (wing at the beak, no props) and from wing-flap/wave (no held props).

- `41-notes-scribble-01.png` — pencil tip just touching the top of the blank notepad page, about to write.
- `42-notes-scribble-02.png` — same pose, pencil moved partway down the page, a few scribble marks now visible.
- `43-notes-scribble-03.png` — same pose, denser scribble marks on the page than frame 2 — loops back to `41` (blank page) to restart the scribble loop.

### 15. Ear cup / "didn't catch that" (2 frames) — one-shot, plays on STT lag or unclear input
One wing raised and pressed to the side of the head near the ear, leaning
slightly in — distinct silhouette from the sleepy pose (no raised wing there)
and from thinking (wing rests at the beak, not the side of the head).

- `44-ear-cup-01.png` — wing cupped to the side of the head, leaning in slightly, uncertain expression.
- `45-ear-cup-02.png` — same pose held a beat longer, with a small "?" question-mark accent floating beside the head to clarify the "didn't catch that" read.

### 16. Hand raise eager (2 frames) — one-shot, eager "pick me" beat
One wing shot straight up overhead, stiff and eager, like an excited student
desperate to answer. Distinct silhouette from wing-flap (both wings, symmetric
arc) and from wave (one wing beside the head, tipping side to side) — here a
single wing goes straight up above the head and stays there.

- `46-hand-raise-eager-01.png` — one wing shooting straight up overhead, big open-mouthed excited smile, eyes wide and bright, body leaning slightly forward and up with eagerness.
- `47-hand-raise-eager-02.png` — same pose held at its peak, biggest happiest smile, character stays firmly grounded (no floating/jump, no sparkles — that look is reserved for the happy-bounce sequence).

### 17. Sleeping (2 frames) — pause-state, fully-asleep hold
The fully-committed "paused for a while" state. Both eyes are drawn fully
CLOSED (simple curved eyelid lines, no pupils, no eye-white at all) — this is
the key differentiator from the sleepy sequence (38-39), whose eyes stay
open/heavy-lidded rather than shut. The body posture is also a distinctly
lopsided slump (head resting against a raised wing, then sagging further),
unlike sleepy's upright symmetric droop, so the two sequences read as clearly
different states at a glance.

- `48-sleeping-01.png` — head tipped to one side, resting against its own raised wing like a pillow, body leaning the same direction, eyes fully closed, beak soft and relaxed.
- `49-sleeping-02.png` — one step deeper: the raised wing has gone limp and slipped down, head sagged further and lower with the chin near the chest, both wings now down at the sides, eyes still fully closed — a visibly deeper, more limp stage of sleep than frame 1.

## Generation + verification notes

- Every call passes `design/duck-sheet-b-clay-3d.png` as `--ref` plus the shared
  character-lock text above, so the character stays on-model across all poses.
- Frame-to-frame deltas are deliberately described as small motion increments
  within each sequence (not scene changes), per the animation-frame requirement.
- After generation, `scripts/strip_bg.py` removes the white `#FFFFFF` backdrop
  via `rembg` + matting decontamination and writes true RGBA PNGs;
  `scripts/verify_alpha.py` checks every file has mode `RGBA` and fully
  transparent corner pixels before landing.
- If a frame drifts off-model (wrong palette, wrong proportions, extra
  limbs/props), it is regenerated individually rather than accepted — this is
  logged in the commit message / report, not silently shipped. Regenerations
  during asn-ek1:
  - `43-notes-scribble-03.png` — the first pass moved the wing/pencil up
    toward the chin and widened the eyes, breaking continuity with frames
    41-42; regenerated with the frame-42 output added as an extra `--ref`
    alongside the duck-sheet reference to lock the pose, which fixed it.
  - `47-hand-raise-eager-02.png` — the first pass drifted into a full
    airborne jump with sparkle accents, overlapping visually with the
    happy-bounce sequence; regenerated with an explicit "stay grounded, no
    sparkles" instruction.
  - `48-49-sleeping-*.png` — the first pass used heavy-lidded-but-open eyes
    (too close to the existing sleepy sequence) and, for frame 2, a floating
    coral "zzz" accent; the eyes were fixed by explicitly requiring fully
    closed curved-eyelid-only eyes with no pupils, and the zzz accent was
    dropped after `scripts/strip_bg.py`'s rembg pass proved unreliable on
    small disconnected floating props (it silently dropped or ghosted the
    letters even at high contrast) — frame 2's differentiation instead comes
    from the raised wing going limp and the head sagging further, a change
    within the main connected body silhouette that mattes cleanly. A
    standalone BRB sign prop (previously `46-brb-sign-01.png`) was generated,
    verified, and landed in an earlier asn-ek1 commit, then removed after a
    design-board scope revision dropped it — see git history for that asset
    if it's ever needed again.

## v3 single-pose engine: the 8 chosen frames (beads asn-q3r, asn-5w3)

asn-3sm's v3 duck engine (per explicit user direction: "ONE static image per
state, animation comes later") replaced the ~26-frame animation-loop
architecture with exactly one static frame per [`DuckState`] plus one per
event pulse -- 7 frames initially, picked from this 49-pose library. Picks
inherited unchanged from the asn-3sm WIP checkpoint (`agent/asn-3sm` @
`0089f88`); asn-q3r's job was verifying/redoing the normalization, not
re-picking poses. Bead asn-5w3 (v5.2 design addition) added an 8th frame,
`CELEBRATE`, for the new tag-approval celebration pulse -- a fresh pick (both
wings fully raised, `13-wing-flap-03.png`), not inherited from the WIP
checkpoint, since that pulse didn't exist yet when asn-3sm's WIP was cut.

| Token | Source pose | Role |
| --- | --- | --- |
| `ATTENTIVE` | `08-listening-intro-01.png` | Base: recording active, default |
| `SLEEP` | `48-sleeping-01.png` | Base: quiet, or either pause kind |
| `THINK` | `23-thinking-01.png` | Base: a summary round is in flight |
| `BLINK` | `05-blink-01.png` | Pulse: a few seconds of audio captured and sent to transcription |
| `NOD` | `16-head-turn-02.png` | Pulse: a final transcription segment landed |
| `RAISE_HAND` | `46-hand-raise-eager-01.png` | Pulse: a new tag entered the thought cloud |
| `WRITE` | `41-notes-scribble-01.png` | Pulse: a summary round completed |
| `CELEBRATE` | `13-wing-flap-03.png` | Pulse: a tag was approved -- both wings up + a vertical happy-bounce (~600ms, 2 bounces) layered on top by `DuckAnimator`, not baked into the pixels |

Pick correction during asn-q3r's lead review: `BLINK` was originally picked as
`07-blink-03.png` in the asn-3sm WIP checkpoint, but that frame is off-model
against the other 6 -- it's a standing duck (legs visible, narrower body,
more saturated color), so the pulse would visibly "change bodies" against
the sitting `ATTENTIVE` base every time it fired. Swapped to `05-blink-01.png`,
which shares the sitting silhouette/bandana of the other 6 and reads as a
true blink (eyes dipped to brown dots, otherwise identical to the idle/
attentive pose). `06-blink-02.png` was also considered and rejected --
heavy-lidded/sleepy read, too close to the `SLEEP` frame.

### Normalization

Because each of the 49 poses above came from an independent AI render call,
they are not pixel-continuous with each other -- the duck sits at a
different scale and vertical offset in different frames. Crossfading
directly between two unnormalized frames makes the duck visibly jump in
size/position when the app swaps states. `scripts/normalize_poses.py`
removes that jump for exactly these 8 frames:

1. Alpha-bbox-crop each source pose (drop the surrounding transparent
   margin).
2. Scale every crop to an **identical height: 432px**.
3. Bottom-center-anchor all 7 on a shared **512x512** transparent canvas,
   with the duck's alpha-bbox bottom edge fixed at **y=488** (24px margin
   from the canvas bottom) — so the pose changes between frames, but the
   duck's apparent size and footing never do.

Output lands directly as the app-ready assets at
`app/src/main/res/drawable-nodpi/duck_{attentive,sleep,think,blink,nod,
raise_hand,write,celebrate}.png`, matching this repo's existing drawable-nodpi
bundling convention (density-independent fixed-pixel bitmaps). Verified with
`scripts/verify_alpha.py` (true RGBA, transparent corners, non-trivial
opaque interior) before landing; re-running the script after the asn-5w3
`FRAMES` addition reproduced byte-identical output for the original 7
(confirmed via `shasum`), so the celebrate addition didn't perturb the
already-landed picks.

Regenerate with:

```
python3 scripts/normalize_poses.py --out-dir ../../../app/src/main/res/drawable-nodpi
```
