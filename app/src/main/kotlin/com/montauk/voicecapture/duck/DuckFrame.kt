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
