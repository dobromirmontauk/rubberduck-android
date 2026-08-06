package com.montauk.voicecapture.duck

/**
 * High-level visual state the duck view renders as (bead asn-3sm, design
 * board section 1): LISTENING (awake, taking in the conversation) / SLEEPY
 * (idle-timeout, drowsy) / THINKING ("taking notes" -- a tag/summary LLM
 * call in flight) / GONE_BRB (recording paused -- duck steps away, a "BRB"
 * sign card stands in).
 */
enum class DuckState { LISTENING, SLEEPY, THINKING, GONE_BRB }

/** What [DuckAnimator] should render for one frame: a specific pose, or the BRB card (no duck frame at all). */
sealed interface DuckVisual {
    data class Pose(val frame: DuckFrame) : DuckVisual
    data object Brb : DuckVisual
}
