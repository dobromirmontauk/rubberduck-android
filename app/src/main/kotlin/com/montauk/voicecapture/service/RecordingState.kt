package com.montauk.voicecapture.service

import com.montauk.voicecapture.audio.AudioRouteType
import com.montauk.voicecapture.session.RecordingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingUiState(
    val isRecording: Boolean = false,
    val sessionId: String? = null,
    val elapsedMs: Long = 0L,
    val mode: RecordingMode = RecordingMode.DEFAULT,
    /**
     * Bead vn-edu.2: which physical device the mic capture is actually
     * routed through right now, per [com.montauk.voicecapture.audio.AudioRouteSelector].
     * Null before a session's route has resolved at all (recording hasn't
     * started, or the debug [com.montauk.voicecapture.audio.FileAudioSource]
     * path, which never routes). Unlike [TranscriptUiState.sourceLabel]
     * (fixed for the whole session, MIC/FILE only), this updates live as
     * Bluetooth devices connect/disconnect mid-session.
     */
    val activeAudioRoute: AudioRouteType? = null,
    /**
     * True once [activeAudioRoute] is [AudioRouteType.BLUETOOTH_SCO] --
     * classic Bluetooth audio is narrowband (~8kHz) even though this app's
     * own PCM stream stays 16kHz, so the recording screen shows a
     * non-blocking quality warning instead of a plain Bluetooth badge.
     */
    val scoNarrowbandWarning: Boolean = false,
    /**
     * True once a mid-session Bluetooth device disappearance has forced a
     * fallback to the phone mic (see `RouteTransition.isDeviceLossFallback`
     * in [com.montauk.voicecapture.audio.AudioRouteController]). Sticky for
     * the rest of the session rather than self-clearing on reconnect -- the
     * point is to tell the user Bluetooth dropped out at some point during
     * this recording, not to flash a badge and disappear.
     */
    val bluetoothDeviceLost: Boolean = false,
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
