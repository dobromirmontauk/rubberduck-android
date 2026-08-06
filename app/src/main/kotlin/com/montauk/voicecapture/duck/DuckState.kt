package com.montauk.voicecapture.duck

/**
 * High-level visual state the duck view renders as (bead asn-3sm, design
 * board section 1): LISTENING (awake, taking in the conversation) / SLEEPY
 * (idle-timeout, drowsy but still recording -- eyes stay open/heavy-lidded,
 * MANIFEST.md sequence 12) / THINKING ("taking notes" -- a tag/summary LLM
 * call in flight) / SLEEPING (recording paused, either kind -- fully
 * asleep, eyes closed, MANIFEST.md sequence 18; a later design-board
 * revision dropped the original standalone "BRB sign card" treatment in
 * favor of the duck visibly nodding off in place).
 */
enum class DuckState { LISTENING, SLEEPY, THINKING, SLEEPING }

/** What [DuckAnimator] should render for one frame: always a specific pose. */
sealed interface DuckVisual {
    data class Pose(val frame: DuckFrame) : DuckVisual
}
