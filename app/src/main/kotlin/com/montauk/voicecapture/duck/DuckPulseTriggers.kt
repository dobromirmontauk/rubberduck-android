package com.montauk.voicecapture.duck

/**
 * Pure Kotlin trigger-decision logic behind bead asn-02h's real event-pulse
 * wiring -- each function/class here answers "given this real pipeline
 * event, should a [DuckPulse] fire (and which)?" without any Compose/Android
 * dependency, so [com.montauk.voicecapture.service.RecordingService] (which
 * has no unit-test coverage of its own -- same as [com.montauk.voicecapture.tags.TagChipRail]/
 * [com.montauk.voicecapture.service.SilenceDetector] pairing untested Service
 * call sites with a tested pure class) can stay a thin integration wire while
 * the actual decision is independently testable. Grows one piece per child
 * bead: [BlinkHeartbeat] (asn-02h.1), [nodPulseForFinalSegment] (asn-02h.2).
 */

/**
 * The transcription heartbeat (bead asn-02h.1, user + lead decision
 * 2026-08-07): throttles [DuckPulse.BLINK] to at most once per [throttleMs]
 * regardless of how often inbound STT partials arrive. [onInboundPartial] is
 * driven exclusively by a RESPONSE received from the STT engine (proves the
 * full mic->socket->engine->response round trip) -- callers must never call
 * this for an outbound audio-chunk send, which would false-reassure when the
 * engine is actually down. No partials arriving at all (quiet) means no
 * calls at all, which correctly means no blinks -- there is no separate
 * "idle" branch to gate here.
 */
class BlinkHeartbeat(private val throttleMs: Long = DEFAULT_THROTTLE_MS) {
    private var lastBlinkAtMs: Long? = null

    /** Call on every inbound (non-final) STT partial response at [nowMs]. Returns true iff this call should trigger [DuckPulse.BLINK] -- i.e. [throttleMs] has elapsed since the last one that did. */
    fun onInboundPartial(nowMs: Long): Boolean {
        val last = lastBlinkAtMs
        if (last != null && nowMs - last < throttleMs) return false
        lastBlinkAtMs = nowMs
        return true
    }

    /** Call at the start of every new recording session so a previous session's throttle window never bleeds into a fresh one. */
    fun reset() {
        lastBlinkAtMs = null
    }

    /** True iff [onInboundPartial] has ever returned true -- test/assertion hook for "no inbound partial ever arrived means no blink ever fired" (asn-02h.1: an outbound audio-chunk send alone must never reach this class at all). */
    fun hasEverBlinked(): Boolean = lastBlinkAtMs != null

    companion object {
        const val DEFAULT_THROTTLE_MS = 2_500L
    }
}

/**
 * A final transcription segment landing always fires [DuckPulse.NOD] (bead
 * asn-02h.2) -- a slower, per-turn beat than [BlinkHeartbeat]'s throttled
 * heartbeat, and unconditional: unlike BLINK there is no cadence to gate,
 * every real `end_of_turn` result from the STT engine is itself already rare
 * enough (a whole spoken turn, not a per-word partial) to warrant its own
 * nod every time. A free function rather than a class since there's no
 * state to carry between calls -- kept here (not inlined at the call site)
 * so [com.montauk.voicecapture.service.RecordingService]'s STT partials
 * collector reads as "ask the trigger layer" the same way it does for
 * [BlinkHeartbeat], and so this mapping has its own test.
 */
fun nodPulseForFinalSegment(): DuckPulse = DuckPulse.NOD
