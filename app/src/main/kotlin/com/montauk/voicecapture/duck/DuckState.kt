package com.montauk.voicecapture.duck

/**
 * The duck's persistent base visual state (bead asn-5w3, one static pose
 * per state -- see [DuckFrame]'s KDoc): [ATTENTIVE] while recording is
 * actively picking up speech, or quiet but still within the leading
 * (invisible) span before the auto-pause fill window starts; [DROWSY] (bead
 * asn-3h6) once continuous quiet enters that trailing fill window -- eyes
 * heavy-lidded but still recording, still awake enough that speech resuming
 * costs nothing; [SLEEP] once auto-pause or a manual pause has actually
 * fired (eyes fully closed -- auto and manual pause are visually identical,
 * see [toDuckState] for exactly where each boundary falls); [THINK] for the
 * span of a summary round's real network
 * call ([com.montauk.voicecapture.service.SummaryCallStateHolder]). A base
 * state persists until it's explicitly changed -- see [DuckAnimationEngine]
 * for how a brief [DuckPulse] can interrupt it without changing it.
 */
enum class DuckState { ATTENTIVE, DROWSY, SLEEP, THINK }

/**
 * A brief one-shot pose that plays over whatever [DuckState] is current,
 * then reverts -- design board v3/v5.2's "event pulses": [BLINK] (a few
 * seconds of audio captured and sent to transcription), [NOD] (a final
 * transcription segment landed), [RAISE_HAND] (a new tag entered the
 * thought cloud), [WRITE] (a summary round completed), [CELEBRATE] (a tag
 * was approved -- both wings up, paired with a vertical happy-bounce
 * animation [DuckAnimator] layers on top of the pose, not baked into the
 * pixels). Every pulse but [CELEBRATE] runs for
 * [DuckAnimationEngine.DEFAULT_PULSE_DURATION_MS]; [CELEBRATE] runs for the
 * shorter [DuckAnimationEngine.CELEBRATE_PULSE_DURATION_MS] to match its
 * ~600ms/2-bounce choreography. See [DuckAnimationEngine.triggerPulse] for
 * the queuing rule when one is already playing.
 */
enum class DuckPulse { BLINK, NOD, RAISE_HAND, WRITE, CELEBRATE }

/** What [DuckAnimator] should render for one frame: always a specific pose. */
sealed interface DuckVisual {
    data class Pose(val frame: DuckFrame) : DuckVisual
}
