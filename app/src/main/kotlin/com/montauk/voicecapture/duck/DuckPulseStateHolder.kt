package com.montauk.voicecapture.duck

import com.montauk.voicecapture.logging.RubberduckLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes real pipeline-triggered [DuckPulseEvent]s to the UI (bead
 * asn-02h), mirroring [com.montauk.voicecapture.service.TagRailStateHolder]'s
 * no-bound-service pattern: [com.montauk.voicecapture.service.RecordingService]
 * calls [emit] directly from the exact real event each pulse represents (an
 * inbound STT partial/final segment, a new tag entering the top set, a tag
 * approval, a summary round completing) so this is the SINGLE SOURCE OF
 * TRUTH [com.montauk.voicecapture.ui.RecordingScreen] reads from -- it
 * replaces the ad-hoc UI-local nonces (`happyBounceTrigger`, the
 * successive-[ThoughtCloudWord]-snapshot diff) that predated this bead,
 * which fired from wherever a Compose callback happened to be wired rather
 * than from the pipeline event itself (see asn-02h's bead notes: the old
 * wiring dropped CELEBRATE entirely when a tag was approved from the debug
 * view's tag rail instead of the duck-view word cloud, since only the
 * latter's tap handler incremented the old counter).
 */
object DuckPulseStateHolder {
    private val _events = MutableStateFlow<DuckPulseEvent?>(null)
    val events: StateFlow<DuckPulseEvent?> = _events.asStateFlow()

    private var nonce = 0

    /**
     * Publishes a fresh [DuckPulseEvent] for [pulse] -- always a new, distinct
     * event (see [DuckPulseEvent]'s own KDoc for why the nonce matters) even
     * if [pulse] repeats back to back. [source] (bead asn-jht) is a short
     * label for which real pipeline event triggered this dispatch (e.g.
     * "final_segment", "tag_approved") -- callers should always pass one;
     * it's optional only so existing/test call sites that don't care about
     * the log line still compile unchanged.
     */
    fun emit(pulse: DuckPulse, source: String? = null) {
        nonce++
        _events.value = DuckPulseEvent(pulse, nonce)
        if (source != null) {
            RubberduckLog.i("DuckPulse", "dispatch", "pulse" to pulse, "source" to source)
        } else {
            RubberduckLog.i("DuckPulse", "dispatch", "pulse" to pulse)
        }
    }

    /** Call at the start of every new recording session so a previous session's last pulse never replays into a fresh one. */
    fun reset() {
        _events.value = null
    }
}
