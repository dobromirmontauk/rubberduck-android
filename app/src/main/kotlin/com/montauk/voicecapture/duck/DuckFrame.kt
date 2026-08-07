package com.montauk.voicecapture.duck

/**
 * One named pose from the clay-duck pose library (`design/clay-poses/`).
 * Pure token -- no Android/Compose/resource dependency, so
 * [DuckAnimationEngine]'s state machine is unit-testable on the plain JVM.
 * [DuckFrameResources] is the only place a [DuckFrame] is mapped to an
 * actual drawable.
 *
 * Bead asn-5w3 (superseding the earlier ~26-frame, multi-frame-per-state
 * loop architecture per explicit user direction: "ONE static image per
 * state, animation comes later"): exactly one frame per [DuckState] plus
 * one per [DuckPulse] -- see [DuckAnimationEngine] for how the two combine.
 * Each of these 9 frames is normalized (alpha-bbox cropped, scaled to equal
 * duck height, bottom-center anchored -- see
 * `design/clay-poses/scripts/normalize_poses.py`) so crossfading between any
 * two of them never jumps in scale/position the way directly crossfading
 * two un-normalized independent nanobanana renders did.
 */
enum class DuckFrame {
    /** Base: recording active, or quiet but still before the auto-pause fill window starts. Source: `08-listening-intro-01.png`. */
    ATTENTIVE,

    /** Base: quiet, inside the trailing auto-pause fill window but not yet paused (bead asn-3h6). Source: `38-sleepy-01.png`. */
    DROWSY,

    /** Base: auto-paused or manually paused. Source: `48-sleeping-01.png`. */
    SLEEP,

    /** Base: a summary round is in flight. Source: `23-thinking-01.png`. */
    THINK,

    /** Pulse: a few seconds of audio captured and sent to transcription. Source: `05-blink-01.png`. */
    BLINK,

    /** Pulse: a final transcription segment landed. Source: `16-head-turn-02.png`. */
    NOD,

    /** Pulse: a new tag entered the thought cloud. Source: `46-hand-raise-eager-01.png`. */
    RAISE_HAND,

    /** Pulse: a summary round completed. Source: `41-notes-scribble-01.png`. */
    WRITE,

    /** Pulse: a tag was approved -- both wings up. Source: `13-wing-flap-03.png`. */
    CELEBRATE,
}
