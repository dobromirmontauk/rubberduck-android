package com.montauk.voicecapture.duck

/**
 * One named pose from the clay-duck pose library (`design/clay-poses/MANIFEST.md`).
 * Pure token -- no Android/Compose/resource dependency, so [DuckAnimationEngine]'s
 * state->sequence logic is unit-testable on the plain JVM. [DuckFrameResources]
 * is the only place a [DuckFrame] is mapped to an actual drawable.
 */
enum class DuckFrame {
    IDLE_BREATHING_1,
    IDLE_BREATHING_2,
    IDLE_BREATHING_3,
    IDLE_BREATHING_4,
    BLINK_1,
    BLINK_2,
    BLINK_3,
    LISTENING_INTRO_1,
    LISTENING_INTRO_2,
    LISTENING_INTRO_3,
    SLEEPY_1,
    SLEEPY_2,
    THINKING_1,
    THINKING_2,
    THINKING_3,
    HAPPY_BOUNCE_1,
    HAPPY_BOUNCE_2,
    HAPPY_BOUNCE_3,
    SLEEPING_1,
    SLEEPING_2,
    HEAD_TURN_1,
    HEAD_TURN_2,
    HEAD_TURN_3,
    HEAD_TURN_4,
    HAND_RAISE_1,
    HAND_RAISE_2,
}

/** Resting/default loop (bead asn-3sm) -- MANIFEST.md sequence 1, frames 01-04. */
internal val IDLE_BREATHING_SEQUENCE = listOf(
    DuckFrame.IDLE_BREATHING_1,
    DuckFrame.IDLE_BREATHING_2,
    DuckFrame.IDLE_BREATHING_3,
    DuckFrame.IDLE_BREATHING_4,
)

/** One-shot blink that interrupts the idle loop periodically -- MANIFEST.md sequence 2, frames 05-07. */
internal val BLINK_SEQUENCE = listOf(DuckFrame.BLINK_1, DuckFrame.BLINK_2, DuckFrame.BLINK_3)

/** One-shot transition played once on entering LISTENING -- MANIFEST.md sequence 3, frames 08-10. */
internal val LISTENING_INTRO_SEQUENCE = listOf(
    DuckFrame.LISTENING_INTRO_1,
    DuckFrame.LISTENING_INTRO_2,
    DuckFrame.LISTENING_INTRO_3,
)

/** Slow-cadence loop for SLEEPY -- MANIFEST.md sequence 12, frames 38-39. */
internal val SLEEPY_SEQUENCE = listOf(DuckFrame.SLEEPY_1, DuckFrame.SLEEPY_2)

/**
 * Loops for THINKING ("taking notes") -- MANIFEST.md sequence 14
 * (notes-scribble, frames 41-43, bead asn-ek1). Originally stood in with the
 * generic "thinking" pose (sequence 7, frames 23-25) until the real
 * writing-on-notepad poses landed; only [DuckFrameResources] changed when
 * they did, not this token list or [DuckAnimationEngine]'s THINKING phase.
 */
internal val THINKING_SEQUENCE = listOf(DuckFrame.THINKING_1, DuckFrame.THINKING_2, DuckFrame.THINKING_3)

/**
 * One-shot squash-stretch bounce (design-board section 1: "Got it!" -- new
 * tag approved / summary bullet added) that can interrupt any other state
 * for its duration -- MANIFEST.md sequence 8, frames 26-28.
 */
internal val HAPPY_BOUNCE_SEQUENCE = listOf(DuckFrame.HAPPY_BOUNCE_1, DuckFrame.HAPPY_BOUNCE_2, DuckFrame.HAPPY_BOUNCE_3)

/**
 * Slow-cadence loop for SLEEPING (recording paused, either kind) -- deeper
 * than SLEEPY: eyes fully closed rather than open/heavy-lidded, per
 * MANIFEST.md sequence 18 (frames 48-49, bead asn-ek1). Replaces this
 * bead's original standalone "BRB sign card" treatment after a design-board
 * revision dropped that prop from scope in favor of the duck visibly
 * nodding off in place.
 */
internal val SLEEPING_SEQUENCE = listOf(DuckFrame.SLEEPING_1, DuckFrame.SLEEPING_2)

/**
 * One-shot "nod" -- design board v2's vitality signal ("healthy pipeline =
 * lively duck": blinks every 3-5s, nods every ~10-15s while LISTENING).
 * Reuses the head-turn sweep (MANIFEST.md sequence 5, frames 15-18) as a
 * stand-in per the spec's own note ("use head-turn or idle frames for the
 * nod until a dedicated pose exists").
 */
internal val NOD_SEQUENCE = listOf(DuckFrame.HEAD_TURN_1, DuckFrame.HEAD_TURN_2, DuckFrame.HEAD_TURN_3, DuckFrame.HEAD_TURN_4)

/**
 * One-shot eager hand-raise (design board v2: a new tag enters the cloud)
 * that can interrupt any other state for its duration, same pattern as
 * [HAPPY_BOUNCE_SEQUENCE] -- MANIFEST.md sequence 17 (hand-raise-eager,
 * frames 46-47, bead asn-ek1).
 */
internal val HAND_RAISE_SEQUENCE = listOf(DuckFrame.HAND_RAISE_1, DuckFrame.HAND_RAISE_2)
