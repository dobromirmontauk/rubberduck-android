package com.montauk.voicecapture.service

import com.montauk.voicecapture.stt.SttConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One finalized line for the live-transcript UI pane. */
data class TranscriptLine(val text: String, val startMs: Long, val endMs: Long)

data class TranscriptUiState(
    val connectionState: SttConnectionState = SttConnectionState.DISABLED,
    val finalLines: List<TranscriptLine> = emptyList(),
    /** The current in-progress (not yet `end_of_turn`) segment, dimmed in the UI; blank when there isn't one. */
    val currentPartial: String = "",
    /**
     * Bead vn-edu.45: word-level-final prefix of [currentPartial], painted
     * solid/stable rather than waiting on `end_of_turn` -- see
     * [com.montauk.voicecapture.stt.TranscriptPartial.stableText]. Blank
     * when there's no current partial, or when the backend hasn't sent
     * per-word finality for it yet (the UI falls back to treating all of
     * [currentPartial] as [partialUnstableTail] in that case, same as the
     * pre-vn-edu.45 whole-partial-dimmed rendering).
     */
    val partialStableText: String = "",
    /** The still-forming tail of [currentPartial] beyond [partialStableText] -- dimmed in the UI. */
    val partialUnstableTail: String = "",
    /** Normalized (0f..1f) RMS from [com.montauk.voicecapture.audio.MicLevelMeter], updated ~every 100ms. */
    val micLevel: Float = 0f,
    /** True when [com.montauk.voicecapture.service.SilenceDetector] says to show the "(silence)" hint. */
    val silenceHintVisible: Boolean = false,
    /**
     * Bead asn-r60: [com.montauk.voicecapture.audio.VoiceActivityDetector.continuousQuietMs],
     * mirrored here so the UI can render a live-ticking "how long quiet"
     * duration without its own StateFlow -- updated from the same
     * mic-level-meter callback that already writes [micLevel]/[silenceHintVisible],
     * so all three stay consistent under the same CAS-safe
     * [TranscriptStateHolder.update]. Keeps growing through
     * [com.montauk.voicecapture.service.RecordingActivityState.AUTO_PAUSED]
     * and resets to 0 the moment VAD reads speech again. [autoPauseFillFraction]
     * is derived from this same value; both are published together.
     */
    val quietDurationMs: Long = 0L,
    /**
     * Bead asn-o63: 0f..1f fill progress toward auto-pause, derived from
     * [quietDurationMs] against the settings-tunable threshold's trailing
     * [com.montauk.voicecapture.settings.AppSecretsStore.AUTO_PAUSE_FILL_DURATION_MS]
     * window -- 0f for the leading (invisible) span of continuous quiet, then
     * ramping 0f->1f as that trailing window elapses, reaching 1f exactly
     * when auto-pause fires. Resets to 0f the instant [quietDurationMs] does
     * (any speech cancels the fill), and stays 0f whenever auto-pause is
     * disabled. Deliberately state-layer-only (bead asn-o63): the pause
     * button's own gradient-fill rendering is one consumer, but this is
     * exposed generically enough that a future re-skin (e.g. the duck
     * restyle, bead asn-3sm) can render it without recomputing the
     * settings-aware math itself.
     */
    val autoPauseFillFraction: Float = 0f,
    /**
     * [com.montauk.voicecapture.audio.AudioSource.deviceLabel] for the session
     * currently recording -- null before a session starts. RecordingScreen's
     * source chip shows "FILE" verbatim when this is "FILE" (debug-only audio
     * injection, bead vn-edu.20); any other value falls back to its existing
     * bluetooth/phone-mic detection instead of trusting a live mic's label.
     */
    val sourceLabel: String? = null,
)

/**
 * Publishes the live-transcript stream to the UI, mirroring how
 * [RecordingStateHolder] publishes recording state -- [RecordingService]
 * owns the [com.montauk.voicecapture.stt.StreamingSttClient] and pushes
 * updates here without the UI needing a bound-service connection.
 *
 * Two independent producers write here concurrently: the STT partial/final
 * stream (a coroutine on [RecordingService]'s lifecycle scope) and the
 * mic-level meter (callbacks from the audio capture thread). [update] uses
 * [MutableStateFlow.update]'s atomic compare-and-set loop rather than a plain
 * read-modify-write so neither producer can silently clobber the other's
 * write under concurrent access.
 */
object TranscriptStateHolder {
    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()

    fun update(transform: (TranscriptUiState) -> TranscriptUiState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = TranscriptUiState()
    }
}
