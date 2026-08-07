"""
Pose list for bead vn-edu.73's v2 nav-icon rework (rejected v1 was a shared
silhouette + generic badge; user wants a distinct yellow duck POSE + a HELD
prop per nav item, clay-style like the existing mascot library). Mirrors
design/clay-poses/scripts/poses.py's CHARACTER_LOCK/BACKDROP_INSTRUCTION
convention so these renders stay on-model with the rest of the app's duck
art, but this is a standalone one-off set (5 icons), not a full sequence
library, so it lives in its own directory rather than clay-poses/.

Every prop is explicitly HELD or WORN, never floating free -- rembg's matting
against a disconnected small floating prop is unreliable (see clay-poses/
MANIFEST.md's note on the dropped BRB-sign prop), so each prompt anchors the
prop to the duck's wing/body so the whole thing mattes as one connected
silhouette.
"""

CHARACTER_LOCK = (
    "A single warm-yellow soft-clay-render duck character, claymation style: "
    "smooth rounded matte clay body, visible tiny clay tool-dimples, soft even "
    "studio lighting, gentle ambient occlusion, coral-orange clay beak, "
    "coral-orange scarf/accent patch on the chest. Round body, small stubby "
    "wings, simple dot eyes. Match the reference image exactly in proportions, "
    "palette, and accessories -- do not change body shape, colors, or the "
    "scarf. Waist-up to full-body view, character centered and fully inside "
    "the frame with clear margin on all sides."
)

BACKDROP_INSTRUCTION = (
    "Background: a single flat, perfectly uniform solid PURE WHITE (#FFFFFF) "
    "studio backdrop, no gradient, no vignette, no texture, no floor line -- "
    "just the flat white field with a soft neutral gray contact shadow "
    "directly beneath the character's feet. This background will be removed "
    "programmatically, so it must be a completely flat, consistent white with "
    "no gradient anywhere near the character's silhouette."
)

PROP_INSTRUCTION = (
    "The prop described below must be HELD directly in the duck's wing/wing-tip "
    "or worn on the body, physically touching and overlapping the duck's "
    "silhouette at all times -- never floating separately or drawn apart from "
    "the body, so the duck and its prop matte out as a single connected shape."
)

# (filename, nav-item, pose-specific prompt)
PROPS = [
    ("mic.png", "new_session",
     "The duck holds a small clay microphone up near its beak in one wing, "
     "as if about to speak or sing into it; head tilted slightly toward the "
     "mic, an eager alert expression, standing pose."),
    ("scroll.png", "sessions",
     "The duck holds an unrolled paper scroll/notes in both wings out in "
     "front of its chest, looking down at it, a small attentive expression, "
     "as if reviewing written notes."),
    ("hammer.png", "settings",
     "The duck holds a small clay hammer in one wing, raised up beside its "
     "head mid-swing as if about to tap something, a focused handyman "
     "expression, standing pose, tool-belt vibe."),
    ("door_in.png", "signin",
     "The duck is stepping forward through a simple open clay doorframe "
     "(a plain rectangular door frame just behind/around the duck, same "
     "warm-neutral clay material), one foot stepped through the threshold "
     "into frame, one wing raised in a small friendly wave, cheerful "
     "expression -- arriving."),
    ("door_out.png", "signout",
     "The duck is stepping away through a simple open clay doorframe (a "
     "plain rectangular door frame just behind/around the duck, same "
     "warm-neutral clay material), body turned three-quarters away with "
     "one foot stepping through the threshold out of frame, head turned "
     "back over its shoulder with one wing raised in a small goodbye wave -- "
     "leaving."),
]
