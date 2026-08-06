package com.montauk.voicecapture.duck

/**
 * The duck's persistent base visual state (bead asn-3sm v3, one static pose
 * per state -- see [DuckFrame]'s KDoc): [ATTENTIVE] while recording is
 * actively picking up speech; [SLEEP] while quiet or paused (either kind --
 * auto or manual pause are visually identical, no separate "sleepy" tier
 * anymore); [THINK] for the span of a summary round's real network call
 * ([com.montauk.voicecapture.service.SummaryCallStateHolder]). A base state
 * persists until it's explicitly changed -- see [DuckAnimationEngine] for
 * how a brief [DuckPulse] can interrupt it without changing it.
 */
enum class DuckState { ATTENTIVE, SLEEP, THINK }

/**
 * A brief (~[DuckAnimationEngine.DEFAULT_PULSE_DURATION_MS]) one-shot pose
 * that plays over whatever [DuckState] is current, then reverts -- design
 * board v3's "event pulses": [BLINK] (a few seconds of audio captured and
 * sent to transcription), [NOD] (a final transcription segment landed),
 * [RAISE_HAND] (a new tag entered the thought cloud), [WRITE] (a summary
 * round completed). See [DuckAnimationEngine.triggerPulse] for the queuing
 * rule when one is already playing.
 */
enum class DuckPulse { BLINK, NOD, RAISE_HAND, WRITE }

/** What [DuckAnimator] should render for one frame: always a specific pose. */
sealed interface DuckVisual {
    data class Pose(val frame: DuckFrame) : DuckVisual
}
