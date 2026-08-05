package com.montauk.voicecapture.service

import com.montauk.voicecapture.stt.SttConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One finalized line for the live-transcript UI pane. */
data class TranscriptLine(val text: String, val startMs: Long, val endMs: Long)

data class TranscriptUiState(
    val connectionState: SttConnectionState = SttConnectionState.DISABLED,
    val finalLines: List<TranscriptLine> = emptyList(),
    /** The current in-progress (not yet `end_of_turn`) segment, dimmed in the UI; blank when there isn't one. */
    val currentPartial: String = "",
)

/**
 * Publishes the live-transcript stream to the UI, mirroring how
 * [RecordingStateHolder] publishes recording state -- [RecordingService]
 * owns the [com.montauk.voicecapture.stt.StreamingSttClient] and pushes
 * updates here without the UI needing a bound-service connection.
 */
object TranscriptStateHolder {
    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()

    fun update(transform: (TranscriptUiState) -> TranscriptUiState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = TranscriptUiState()
    }
}
