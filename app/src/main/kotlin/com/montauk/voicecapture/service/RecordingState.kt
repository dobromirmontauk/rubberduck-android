package com.montauk.voicecapture.service

import com.montauk.voicecapture.session.RecordingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingUiState(
    val isRecording: Boolean = false,
    val sessionId: String? = null,
    val elapsedMs: Long = 0L,
    val mode: RecordingMode = RecordingMode.DEFAULT,
)

/**
 * Publishes [RecordingService]'s state to the UI without requiring a bound
 * service connection. Simple and sufficient for a single-activity app;
 * revisit with a bound service / Messenger if a second UI surface needs it.
 */
object RecordingStateHolder {
    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    fun update(transform: (RecordingUiState) -> RecordingUiState) {
        _state.value = transform(_state.value)
    }
}
