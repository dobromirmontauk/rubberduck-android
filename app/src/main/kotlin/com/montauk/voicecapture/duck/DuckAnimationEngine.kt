package com.montauk.voicecapture.duck

/**
 * Pure Kotlin event-driven state machine behind the duck's single-frame pose
 * (bead asn-5w3, superseding the earlier multi-frame-per-state loop engine
 * per explicit user direction: "ONE static image per state, no loops -- the
 * state machine is EVENT-DRIVEN"). No Compose/Android dependency --
 * unit-testable on the plain JVM.
 *
 * [state] (set via [setState]) is a persistent base pose --
 * [DuckState.ATTENTIVE]/[DuckState.SLEEP]/[DuckState.THINK] -- that [tick]
 * renders whenever no [DuckPulse] is currently playing. [triggerPulse]
 * layers a brief one-shot pose on top without changing [state] itself; once
 * it expires, [tick] reverts to whatever [state] is current at that moment
 * (not necessarily what it was when the pulse started -- a pulse never
 * rewinds a base-state change that happened underneath it). Every pulse
 * plays for [pulseDurationMs] except [DuckPulse.CELEBRATE], which plays for
 * the shorter [celebratePulseDurationMs] -- matching its own ~600ms/
 * 2-bounce choreography (see [DuckAnimator]'s celebrate-bounce animation,
 * which reads [activePulseElapsedMs] to time itself off this same clock) --
 * and [DuckPulse.BLINK], which plays for the shorter [blinkPulseDurationMs]
 * (bead asn-3gr: the original 700ms hold, paired with a plain closed-eye
 * asset, read as a "blank stare" glitch rather than a blink -- see
 * `design/clay-poses/MANIFEST.md`'s blink re-pick notes). [DuckAnimator]
 * additionally shortens BLINK's own crossfade
 * ([DuckAnimationEngine.BLINK_CROSSFADE_MS]) so the whole blink -- fade in,
 * brief hold, fade out -- reads as one quick deliberate motion rather than a
 * long static dip.
 *
 * **Queuing** (the bead's own spec: "event pulses queue politely -- don't
 * interrupt mid-pulse, drop stale ones"): [triggerPulse] while one is
 * already playing doesn't interrupt it -- it replaces whatever was
 * previously queued (if anything) with the new request, so at most one
 * pulse is ever waiting its turn, and only the *most recent* one actually
 * plays next; anything queued-and-then-superseded is silently dropped.
 *
 * [tick] is a pure function of "now", so a test can assert behavior by
 * advancing a synthetic clock without any real `delay()`.
 * [msUntilNextTransition] tells the caller exactly how long until the
 * current pulse (if any) will expire, so [DuckAnimator] can schedule a
 * single one-shot wake-up instead of polling on a continuous frame-tick
 * loop -- deliberately: a perpetual `delay()`-driven poll running for the
 * whole lifetime of the recording screen was exactly the kind of thing that
 * left a stuck resource behind across `ComposeTestRule` test boundaries --
 * an event-driven engine with no base-state animation has no need for
 * continuous polling at all.
 *
 * Not thread-safe -- same single-owner-coroutine expectation as
 * [com.montauk.voicecapture.tags.TagTracker].
 */
class DuckAnimationEngine(
    initialState: DuckState = DuckState.ATTENTIVE,
    private val pulseDurationMs: Long = DEFAULT_PULSE_DURATION_MS,
    private val celebratePulseDurationMs: Long = CELEBRATE_PULSE_DURATION_MS,
    private val blinkPulseDurationMs: Long = BLINK_PULSE_DURATION_MS,
) {
    var state: DuckState = initialState
        private set

    private var activePulse: DuckPulse? = null
    private var pulseStartedAtMs: Long = 0L
    private var pulseExpiresAtMs: Long = 0L
    private var queuedPulse: DuckPulse? = null

    /** Switches the persistent base pose -- takes visual effect immediately unless a pulse is currently playing (see the class KDoc). */
    fun setState(newState: DuckState) {
        state = newState
    }

    /**
     * Plays [pulse] for [pulseDurationMs] (or [celebratePulseDurationMs] for
     * [DuckPulse.CELEBRATE]) over whatever is currently showing. If another
     * pulse is already playing, [pulse] replaces whatever was queued
     * (dropping it, if anything was) rather than interrupting the one in
     * progress -- call [tick] afterward (or on the next frame) to pick up
     * the change once the current pulse expires.
     */
    fun triggerPulse(pulse: DuckPulse, nowMs: Long) {
        if (activePulse != null) {
            queuedPulse = pulse
            return
        }
        startPulse(pulse, nowMs)
    }

    private fun startPulse(pulse: DuckPulse, nowMs: Long) {
        activePulse = pulse
        pulseStartedAtMs = nowMs
        pulseExpiresAtMs = nowMs + durationFor(pulse)
    }

    /** Advances past an expired pulse (promoting whatever's queued, if anything) and returns what should render at [nowMs]. */
    fun tick(nowMs: Long): DuckVisual.Pose {
        val pulse = activePulse
        if (pulse != null && nowMs >= pulseExpiresAtMs) {
            activePulse = null
            val next = queuedPulse
            queuedPulse = null
            if (next != null) {
                startPulse(next, nowMs)
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

    /**
     * ms elapsed since the currently active pulse started, or `null` if no
     * pulse is playing. [DuckAnimator] reads this while [activePulse] is
     * [DuckPulse.CELEBRATE] to time the vertical happy-bounce it layers over
     * the pose -- see [DuckCelebrateBounce].
     */
    fun activePulseElapsedMs(nowMs: Long): Long? {
        if (activePulse == null) return null
        return (nowMs - pulseStartedAtMs).coerceAtLeast(0L)
    }

    private fun durationFor(pulse: DuckPulse): Long = when (pulse) {
        DuckPulse.CELEBRATE -> celebratePulseDurationMs
        DuckPulse.BLINK -> blinkPulseDurationMs
        else -> pulseDurationMs
    }

    companion object {
        const val DEFAULT_PULSE_DURATION_MS = 700L
        const val CELEBRATE_PULSE_DURATION_MS = 600L

        /**
         * Bead asn-3gr: BLINK's own shorter pulse duration -- the generic
         * 700ms hold (still used by NOD/RAISE_HAND/WRITE) made a single
         * static closed-eye frame sit on screen long enough to read as a
         * glitchy "blank stare" rather than a blink, confirmed by asn-04j's
         * log analysis (13 dispatches x 700ms covering ~28% of a 33s
         * ATTENTIVE window). Paired with [BLINK_CROSSFADE_MS] below, the
         * total perceived blink (fade in + hold + fade out) lands around
         * 300 + 110 =~ 410ms -- inside the ~350-450ms target for a
         * deliberate blink.
         */
        const val BLINK_PULSE_DURATION_MS = 300L

        const val DEFAULT_CROSSFADE_MS = 220L

        /** Bead asn-3gr: faster crossfade [DuckAnimator] uses specifically for transitions into/out of [DuckFrame.BLINK] -- see [BLINK_PULSE_DURATION_MS]. */
        const val BLINK_CROSSFADE_MS = 110L
    }
}

private fun DuckState.toFrame(): DuckFrame = when (this) {
    DuckState.ATTENTIVE -> DuckFrame.ATTENTIVE
    DuckState.DROWSY -> DuckFrame.DROWSY
    DuckState.SLEEP -> DuckFrame.SLEEP
    DuckState.THINK -> DuckFrame.THINK
    // Bead asn-dp2.5: same DuckFrame.WRITE pose DuckPulse.WRITE already
    // renders (see DuckState's own KDoc) -- held rather than momentary.
    DuckState.WRITE -> DuckFrame.WRITE
}

private fun DuckPulse.toFrame(): DuckFrame = when (this) {
    DuckPulse.BLINK -> DuckFrame.BLINK
    DuckPulse.NOD -> DuckFrame.NOD
    DuckPulse.RAISE_HAND -> DuckFrame.RAISE_HAND
    DuckPulse.WRITE -> DuckFrame.WRITE
    DuckPulse.CELEBRATE -> DuckFrame.CELEBRATE
}
