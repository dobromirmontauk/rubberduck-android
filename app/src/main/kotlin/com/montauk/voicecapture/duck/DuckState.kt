package com.montauk.voicecapture.duck

/**
 * High-level visual state the duck view renders as (bead asn-3sm): LISTENING
 * (awake, taking in the conversation) / SLEEPY (idle-timeout, drowsy) /
 * GONE_BRB (recording paused -- duck steps away, a "BRB" sign card stands in).
 */
enum class DuckState { LISTENING, SLEEPY, GONE_BRB }

/** What [DuckAnimator] should render for one frame: a specific pose, or the BRB card (no duck frame at all). */
sealed interface DuckVisual {
    data class Pose(val frame: DuckFrame) : DuckVisual
    data object Brb : DuckVisual
}
