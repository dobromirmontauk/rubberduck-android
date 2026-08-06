package com.montauk.voicecapture.duck

/**
 * One named pose from the clay-duck pose library (`design/clay-poses/`).
 * Pure token -- no Android/Compose/resource dependency, so
 * [DuckAnimationEngine]'s state machine is unit-testable on the plain JVM.
 * [DuckFrameResources] is the only place a [DuckFrame] is mapped to an
 * actual drawable.
 *
 * Bead asn-3sm v3 (superseding the earlier ~49-frame, multi-frame-per-state
 * loop architecture per explicit user direction: "ONE static image per
 * state, animation comes later"): exactly one frame per [DuckState] plus one
 * per [DuckPulse] -- see [DuckAnimationEngine] for how the two combine. Each
 * of the 7 frames here is normalized (alpha-bbox cropped, scaled to equal
 * duck height, bottom-center anchored) so crossfading between any two of
 * them never jumps in scale/position the way directly crossfading two
 * un-normalized independent nanobanana renders did.
 */
enum class DuckFrame {
    /** Base: recording active, default. Source: `08-listening-intro-01.png`. */
    ATTENTIVE,

    /** Base: quiet, or either pause kind. Source: `48-sleeping-01.png`. */
    SLEEP,

    /** Base: a summary round is in flight. Source: `23-thinking-01.png`. */
    THINK,

    /** Pulse: a few seconds of audio captured and sent to transcription. Source: `07-blink-03.png`. */
    BLINK,

    /** Pulse: a final transcription segment landed. Source: `16-head-turn-02.png`. */
    NOD,

    /** Pulse: a new tag entered the thought cloud. Source: `46-hand-raise-eager-01.png`. */
    RAISE_HAND,

    /** Pulse: a summary round completed. Source: `41-notes-scribble-01.png`. */
    WRITE,
}
