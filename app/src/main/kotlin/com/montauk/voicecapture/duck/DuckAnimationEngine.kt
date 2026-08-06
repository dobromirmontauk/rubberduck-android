package com.montauk.voicecapture.duck

/**
 * Pure Kotlin event-driven state machine behind the duck's single-frame pose
 * (bead asn-3sm v3, superseding the earlier multi-frame-per-state loop
 * engine per explicit user direction: "ONE static image per state, no
 * loops -- the state machine is EVENT-DRIVEN"). No Compose/Android
 * dependency -- unit-testable on the plain JVM.
 *
 * [state] (set via [setState]) is a persistent base pose -- [DuckState.ATTENTIVE]/
 * [DuckState.SLEEP]/[DuckState.THINK] -- that [tick] renders whenever no
 * [DuckPulse] is currently playing. [triggerPulse] layers a brief
 * ([pulseDurationMs]) one-shot pose on top without changing [state] itself;
 * once it expires, [tick] reverts to whatever [state] is current at that
 * moment (not necessarily what it was when the pulse started -- a pulse
 * never rewinds a base-state change that happened underneath it).
 *
 * **Queuing** (the bead's own spec: "event pulses queue politely -- don't
 * interrupt mid-pulse, drop stale ones"): [triggerPulse] while one is
 * already playing doesn't interrupt it -- it replaces whatever was
 * previously queued (if anything) with the new request, so at most one
 * pulse is ever waiting its turn, and only the *most recent* one actually
 * plays next; anything queued-and-then-superseded is silently dropped.
 *
 * [tick] is a pure function of "now" (like the previous engine), so a test
 * can assert behavior by advancing a synthetic clock without any real
 * `delay()`. [msUntilNextTransition] tells the caller exactly how long until
 * the current pulse (if any) will expire, so [DuckAnimator] can schedule a
 * single one-shot wake-up instead of polling on a continuous frame-tick
 * loop -- deliberately: a perpetual `delay()`-driven poll running for the
 * whole lifetime of the recording screen was exactly the kind of thing that
 * left a stuck resource behind across `ComposeTestRule` test boundaries
 * (see [ThoughtCloud]/[ZzTrail]'s own history with `rememberInfiniteTransition`
 * for the sibling version of this same lesson) -- an event-driven engine
 * with no base-state animation has no need for continuous polling at all.
 *
 * Not thread-safe -- same single-owner-coroutine expectation as
 * [com.montauk.voicecapture.tags.TagTracker].
 */
class DuckAnimationEngine(
    initialState: DuckState = DuckState.ATTENTIVE,
    private val pulseDurationMs: Long = DEFAULT_PULSE_DURATION_MS,
) {
    var state: DuckState = initialState
        private set

    private var activePulse: DuckPulse? = null
    private var pulseExpiresAtMs: Long = 0L
    private var queuedPulse: DuckPulse? = null

    /** Switches the persistent base pose -- takes visual effect immediately unless a pulse is currently playing (see the class KDoc). */
    fun setState(newState: DuckState) {
        state = newState
    }

    /**
     * Plays [pulse] for [pulseDurationMs] over whatever is currently
     * showing. If another pulse is already playing, [pulse] replaces
     * whatever was queued (dropping it, if anything was) rather than
     * interrupting the one in progress -- call [tick] afterward (or on the
     * next frame) to pick up the change once the current pulse expires.
     */
    fun triggerPulse(pulse: DuckPulse, nowMs: Long) {
        if (activePulse != null) {
            queuedPulse = pulse
            return
        }
        activePulse = pulse
        pulseExpiresAtMs = nowMs + pulseDurationMs
    }

    /** Advances past an expired pulse (promoting whatever's queued, if anything) and returns what should render at [nowMs]. */
    fun tick(nowMs: Long): DuckVisual.Pose {
        val pulse = activePulse
        if (pulse != null && nowMs >= pulseExpiresAtMs) {
            activePulse = null
            val next = queuedPulse
            queuedPulse = null
            if (next != null) {
                activePulse = next
                pulseExpiresAtMs = nowMs + pulseDurationMs
            }
        }
        val frame = activePulse?.toFrame() ?: state.toFrame()
        return DuckVisual.Pose(frame)
    }

    /**
     * ms from [nowMs] until the current pulse expires -- exactly how long
     * [DuckAnimator] should sleep before calling [tick] again -- or `null`
     * when nothing is scheduled (no pulse playing, so nothing will change
     * until the next external [setState]/[triggerPulse] call). Reflects
     * [tick]'s most recent resolution, so call [tick] first if a pulse may
     * have just expired.
     */
    fun msUntilNextTransition(nowMs: Long): Long? {
        if (activePulse == null) return null
        return (pulseExpiresAtMs - nowMs).coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_PULSE_DURATION_MS = 700L
        const val DEFAULT_CROSSFADE_MS = 200L
    }
}

private fun DuckState.toFrame(): DuckFrame = when (this) {
    DuckState.ATTENTIVE -> DuckFrame.ATTENTIVE
    DuckState.SLEEP -> DuckFrame.SLEEP
    DuckState.THINK -> DuckFrame.THINK
}

private fun DuckPulse.toFrame(): DuckFrame = when (this) {
    DuckPulse.BLINK -> DuckFrame.BLINK
    DuckPulse.NOD -> DuckFrame.NOD
    DuckPulse.RAISE_HAND -> DuckFrame.RAISE_HAND
    DuckPulse.WRITE -> DuckFrame.WRITE
}
