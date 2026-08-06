package com.montauk.voicecapture.duck

/**
 * Pure Kotlin frame-by-frame sequencer driving the duck's pose (bead
 * asn-3sm). No Compose/Android dependency -- the state->sequence mapping and
 * frame timing are unit-testable on the plain JVM, without Robolectric.
 * [DuckAnimator] drives this on a per-frame clock and renders whatever
 * [tick] returns.
 *
 * - **LISTENING**: plays the one-shot [LISTENING_INTRO_SEQUENCE] once on
 *   entry, then settles into a looping [IDLE_BREATHING_SEQUENCE], briefly
 *   interrupted by a one-shot [BLINK_SEQUENCE] every [blinkEveryMs].
 * - **SLEEPY**: loops [SLEEPY_SEQUENCE] at the slower [sleepyFrameDurationMs]
 *   cadence (spec: "slow cadence").
 * - **THINKING**: loops [THINKING_SEQUENCE] ("taking notes" stand-in) at the
 *   normal [frameDurationMs] cadence, for as long as [state] stays THINKING.
 * - **GONE_BRB**: no duck frame at all -- [tick] returns [DuckVisual.Brb].
 *
 * [triggerHappyBounce] layers a one-shot [HAPPY_BOUNCE_SEQUENCE] on top of
 * whichever of the above is current -- design-board section 1's "Got it!"
 * beat (a new tag approved / summary bullet added) -- without changing
 * [state] itself: [tick] checks for a pending bounce before consulting
 * [phase] at all, and once the bounce's frames are exhausted, resumes
 * exactly where the underlying phase would have been (recomputed fresh from
 * [state], not literally paused-and-resumed, since a bounce is expected to
 * be rare and brief enough that the tiny discontinuity this causes -- e.g.
 * IDLE_BREATHING restarting its cycle rather than resuming mid-cycle -- is
 * not visible in practice).
 *
 * [isCrossfading] reports whether the most recent [setState] transition is
 * still inside its crossfade window. The actual pixel crossfade between the
 * duck and the BRB card is left to the Compose layer ([DuckAnimator] uses
 * `Crossfade`) -- this just lets a plain-JVM test assert that a state change
 * was registered as a transition, without needing Compose at all.
 *
 * Not thread-safe -- same single-owner-coroutine expectation as
 * [com.montauk.voicecapture.tags.TagTracker].
 */
class DuckAnimationEngine(
    initialState: DuckState = DuckState.LISTENING,
    private val frameDurationMs: Long = DEFAULT_FRAME_DURATION_MS,
    private val sleepyFrameDurationMs: Long = DEFAULT_SLEEPY_FRAME_DURATION_MS,
    private val blinkEveryMs: Long = DEFAULT_BLINK_EVERY_MS,
    private val crossfadeMs: Long = DEFAULT_CROSSFADE_MS,
) {
    private sealed interface Phase {
        val startedAtMs: Long
    }
    private data class ListeningIntroPhase(override val startedAtMs: Long) : Phase
    private data class IdlePhase(override val startedAtMs: Long) : Phase
    private data class BlinkPhase(override val startedAtMs: Long) : Phase
    private data class SleepyPhase(override val startedAtMs: Long) : Phase
    private data class ThinkingPhase(override val startedAtMs: Long) : Phase
    private data class BrbPhase(override val startedAtMs: Long) : Phase

    private var happyBounceStartedAtMs: Long? = null

    var state: DuckState = initialState
        private set

    // Lazily established on the first setState/tick call using the CALLER's
    // clock, not a baked-in 0L -- callers pass real values (System.currentTimeMillis()
    // in production, small synthetic values in tests), and seeding startedAtMs
    // from an arbitrary epoch would make frame-index math (elapsed-since-phase-start)
    // nonsensical on the very first tick.
    private var phase: Phase? = null
    private var stateEnteredAtMs: Long = 0L

    /** Switches the target [DuckState] at [nowMs] -- a no-op if already in that state (and already initialized). */
    fun setState(newState: DuckState, nowMs: Long) {
        if (newState == state && phase != null) return
        state = newState
        stateEnteredAtMs = nowMs
        phase = initialPhaseFor(newState, nowMs)
    }

    /** True while [nowMs] is still within [crossfadeMs] of the most recent [setState] transition. */
    fun isCrossfading(nowMs: Long): Boolean {
        if (phase == null) return false
        return nowMs - stateEnteredAtMs < crossfadeMs
    }

    /** Layers a one-shot [HAPPY_BOUNCE_SEQUENCE] on top of whatever is currently playing -- see the class KDoc. */
    fun triggerHappyBounce(nowMs: Long) {
        happyBounceStartedAtMs = nowMs
    }

    /** Advances frame timing to [nowMs] and returns what [DuckAnimator] should render. */
    fun tick(nowMs: Long): DuckVisual {
        val bounceStart = happyBounceStartedAtMs
        if (bounceStart != null) {
            val idx = ((nowMs - bounceStart) / frameDurationMs).toInt()
            if (idx >= HAPPY_BOUNCE_SEQUENCE.size) {
                happyBounceStartedAtMs = null
            } else {
                return DuckVisual.Pose(HAPPY_BOUNCE_SEQUENCE[idx])
            }
        }
        if (phase == null) setState(state, nowMs)
        return when (val p = phase!!) {
            is BrbPhase -> DuckVisual.Brb
            is ThinkingPhase -> {
                val idx = (((nowMs - p.startedAtMs) / frameDurationMs) % THINKING_SEQUENCE.size).toInt()
                DuckVisual.Pose(THINKING_SEQUENCE[idx])
            }
            is ListeningIntroPhase -> {
                val idx = ((nowMs - p.startedAtMs) / frameDurationMs).toInt()
                if (idx >= LISTENING_INTRO_SEQUENCE.size) {
                    phase = IdlePhase(nowMs)
                    tick(nowMs)
                } else {
                    DuckVisual.Pose(LISTENING_INTRO_SEQUENCE[idx])
                }
            }
            is IdlePhase -> {
                val elapsed = nowMs - p.startedAtMs
                if (elapsed >= blinkEveryMs) {
                    phase = BlinkPhase(nowMs)
                    tick(nowMs)
                } else {
                    val idx = ((elapsed / frameDurationMs) % IDLE_BREATHING_SEQUENCE.size).toInt()
                    DuckVisual.Pose(IDLE_BREATHING_SEQUENCE[idx])
                }
            }
            is BlinkPhase -> {
                val idx = ((nowMs - p.startedAtMs) / frameDurationMs).toInt()
                if (idx >= BLINK_SEQUENCE.size) {
                    phase = IdlePhase(nowMs)
                    tick(nowMs)
                } else {
                    DuckVisual.Pose(BLINK_SEQUENCE[idx])
                }
            }
            is SleepyPhase -> {
                val idx = (((nowMs - p.startedAtMs) / sleepyFrameDurationMs) % SLEEPY_SEQUENCE.size).toInt()
                DuckVisual.Pose(SLEEPY_SEQUENCE[idx])
            }
        }
    }

    private fun initialPhaseFor(state: DuckState, nowMs: Long): Phase = when (state) {
        DuckState.LISTENING -> ListeningIntroPhase(nowMs)
        DuckState.SLEEPY -> SleepyPhase(nowMs)
        DuckState.THINKING -> ThinkingPhase(nowMs)
        DuckState.GONE_BRB -> BrbPhase(nowMs)
    }

    companion object {
        const val DEFAULT_FRAME_DURATION_MS = 180L
        const val DEFAULT_SLEEPY_FRAME_DURATION_MS = 900L
        const val DEFAULT_BLINK_EVERY_MS = 4_000L
        const val DEFAULT_CROSSFADE_MS = 260L
    }
}
