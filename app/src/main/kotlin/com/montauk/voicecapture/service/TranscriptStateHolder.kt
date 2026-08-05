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
    /** Normalized (0f..1f) RMS from [com.montauk.voicecapture.audio.MicLevelMeter], updated ~every 100ms. */
    val micLevel: Float = 0f,
    /** True when [com.montauk.voicecapture.service.SilenceDetector] says to show the "(silence)" hint. */
    val silenceHintVisible: Boolean = false,
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
