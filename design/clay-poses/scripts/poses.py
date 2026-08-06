"""
Pose list for the clay duck animation library (vn-edu.65).

This is the single source of truth for the 40 poses: it must stay in sync
with MANIFEST.md. Each entry drives both generation (generate_poses.py) and
the output filename.

`prompt` is the pose-specific instruction appended to the shared character
lock + backdrop instruction (see CHARACTER_LOCK / BACKDROP_INSTRUCTION below)
before being sent to the nanobanana direct API.
"""

CHARACTER_LOCK = (
    "A single warm-yellow soft-clay-render duck character, claymation style: "
    "smooth rounded matte clay body, visible tiny clay tool-dimples, soft even "
    "studio lighting, gentle ambient occlusion, coral-orange clay beak, "
    "coral-orange scarf/accent patch on the chest. Round body, small stubby "
    "wings, simple dot eyes. Match the reference image exactly in proportions, "
    "palette, and accessories — do not change body shape, colors, or add/remove "
    "any accessory. Waist-up view, 3/4-front angle, character centered and "
    "fully inside the frame with clear margin on all sides, facing slightly "
    "toward camera-left, unless stated otherwise below."
)

BACKDROP_INSTRUCTION = (
    "Background: a single flat, perfectly uniform solid PURE WHITE (#FFFFFF) "
    "studio backdrop, no gradient, no vignette, no texture, no props, no floor "
    "line — just the flat white field with a soft neutral gray contact shadow "
    "directly beneath the character's feet. Do not let the background tint or "
    "bounce any color onto the character; the clay body must stay pure warm "
    "yellow with no color cast from the background. This background will be "
    "removed programmatically, so it must be a completely flat, consistent "
    "white with no gradient anywhere near the character's silhouette."
)

# (filename, sequence, pose-specific prompt)
POSES = [
    ("01-idle-breathing-01.png", "idle-breathing",
     "Neutral resting pose: chest at rest, eyes soft and half-lidded, calm, standing still."),
    ("02-idle-breathing-02.png", "idle-breathing",
     "Same neutral resting pose, but chest very slightly raised (early inhale) — a tiny, subtle change from a fully relaxed chest."),
    ("03-idle-breathing-03.png", "idle-breathing",
     "Same resting pose, chest at its highest point (peak inhale), shoulders and wings just barely lifted from the resting frame."),
    ("04-idle-breathing-04.png", "idle-breathing",
     "Same resting pose, chest lowering back down from the peak toward neutral (exhale), roughly halfway between peak inhale and fully at rest."),

    ("05-blink-01.png", "blink",
     "Neutral resting pose identical to the idle frame, eyes fully open."),
    ("06-blink-02.png", "blink",
     "Identical pose to the previous frame, but eyes half-closed, mid-blink."),
    ("07-blink-03.png", "blink",
     "Identical pose to the previous frames, but eyes fully closed for a single held blink frame."),

    ("08-listening-intro-01.png", "listening-intro",
     "Head just beginning to tilt and perk up from a neutral resting pose, eyes widening slightly, alertness starting."),
    ("09-listening-intro-02.png", "listening-intro",
     "Head tilted further than the previous frame, one wing beginning to lift up toward the side of the head."),
    ("10-listening-intro-03.png", "listening-intro",
     "Fully alert listening pose: head tilted, one wing resting near the ear/side of the head, eyes wide and attentive, two small simple sound-wave arcs drawn beside the head."),

    ("11-wing-flap-01.png", "wing-flap",
     "Both wings resting down at the character's sides, neutral standing pose."),
    ("12-wing-flap-02.png", "wing-flap",
     "Same standing pose, but both wings lifting outward and upward, roughly halfway to fully raised."),
    ("13-wing-flap-03.png", "wing-flap",
     "Same standing pose, both wings fully raised and spread outward at the peak of a cheerful flap."),
    ("14-wing-flap-04.png", "wing-flap",
     "Same standing pose, both wings lowering back down from fully raised, roughly halfway back toward resting at the sides."),

    ("15-head-turn-01.png", "head-turn",
     "Standing neutral body, head turned to look toward camera-right (the character's own left), eyes following the head."),
    ("16-head-turn-02.png", "head-turn",
     "Same standing body, head partway back toward facing forward, still turned slightly toward camera-right."),
    ("17-head-turn-03.png", "head-turn",
     "Same standing body, head facing straight forward toward the camera, centered."),
    ("18-head-turn-04.png", "head-turn",
     "Same standing body, head turned to look toward camera-left (the character's own right), mirroring the angle of the first frame in this sequence."),

    ("19-talk-viseme-01.png", "talk-viseme",
     "Standing neutral pose, beak fully closed in a resting/silent shape."),
    ("20-talk-viseme-02.png", "talk-viseme",
     "Same standing pose, beak slightly open with a small gap, as if mid soft-consonant sound."),
    ("21-talk-viseme-03.png", "talk-viseme",
     "Same standing pose, beak wide open in an open-vowel shape — the widest open-mouth frame in this set."),
    ("22-talk-viseme-04.png", "talk-viseme",
     "Same standing pose, beak at a medium-open position, between fully open and fully closed, as if mid-word."),

    ("23-thinking-01.png", "thinking",
     "Duck looking up and off to one side, one wing-tip just beginning to lift toward the beak, thoughtful expression starting."),
    ("24-thinking-02.png", "thinking",
     "Same pose progressed further: wing-tip now resting against the side of the beak, head tilted a bit more, a small simple thought-swirl icon beginning to appear above the head."),
    ("25-thinking-03.png", "thinking",
     "Deep-in-thought hold: eyes narrowed slightly, wing-tip still resting at the beak, the thought-swirl icon above the head fully formed and slightly larger than the previous frame."),

    ("26-happy-bounce-01.png", "happy-bounce",
     "Anticipation/squash pose: the character's whole body compressed slightly shorter and wider as if about to jump, beak just starting to curl into a smile."),
    ("27-happy-bounce-02.png", "happy-bounce",
     "Apex of a joyful bounce: body stretched slightly taller, a small gap beneath the feet and the ground shadow suggesting it is airborne, beak wide open in a big cheerful smile, eyes closed happily, a few small sparkle accents around the head."),
    ("28-happy-bounce-03.png", "happy-bounce",
     "Landing/settle pose: body back on the ground at normal height, smile softened to a warm content grin, sparkles mostly faded, relaxed satisfied stance."),

    ("29-waddle-01.png", "waddle",
     "Walking/waddling pose: weight shifted onto the left foot, body leaning slightly to the left, right foot lifted slightly off the ground."),
    ("30-waddle-02.png", "waddle",
     "Mid-step waddling pose: both feet roughly together beneath an upright body, a transitional in-between stance."),
    ("31-waddle-03.png", "waddle",
     "Walking/waddling pose: weight shifted onto the right foot, body leaning slightly to the right, left foot lifted slightly off the ground — mirror image of the left-leaning frame."),
    ("32-waddle-04.png", "waddle",
     "Mid-step waddling pose: both feet roughly together beneath an upright body, a transitional in-between stance mirroring the earlier mid-step frame."),

    ("33-wave-01.png", "wave",
     "One wing raised up beside the head, just starting a friendly wave, a small warm smile on the beak."),
    ("34-wave-02.png", "wave",
     "Same raised-wing pose, but the wing tipped over to one side, mid-wave motion."),
    ("35-wave-03.png", "wave",
     "Same raised-wing pose, but the wing tipped to the opposite side from the previous frame, completing the wave motion, with a big warm smile."),

    ("36-alert-01.png", "alert",
     "Sudden startled reaction: eyes snapped wide open, body slightly pulled back and tensed."),
    ("37-alert-02.png", "alert",
     "Held alert stance following the startle: eyes still wide, both wings slightly spread out and away from the body, fully on-guard posture."),

    ("38-sleepy-01.png", "sleepy",
     "Drowsy pose: eyelids drooping heavily, head starting to droop and lower, sluggish slouched posture."),
    ("39-sleepy-02.png", "sleepy",
     "Deeper drowsy pose: eyes nearly fully closed, head resting low and tilted to one side, a small simple 'z' doze icon near the head."),

    ("40-challenge-01.png", "challenge",
     "Skeptical devil's-advocate pose, clearly distinct from every pose above: one eyebrow-equivalent line arched upward, eyes narrowed slightly, both wings crossed together in front of the chest, beak held in a flat, pursed, unconvinced line."),
]

assert len(POSES) == 40, f"expected 40 poses, found {len(POSES)}"
