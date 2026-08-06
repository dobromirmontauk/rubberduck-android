package com.montauk.voicecapture.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bead asn-r60: the recording session's current voice-activity/pause state,
 * VAD-driven ([com.montauk.voicecapture.audio.VoiceActivityDetector]) even
 * outside any pause -- [SPEAKING]/[QUIET] update continuously while
 * recording is live, not just as a side effect of the auto-pause feature.
 * [AUTO_PAUSED]/[USER_PAUSED] are the two pause states this bead adds; see
 * [RecordingService]'s pause-handling KDoc for the hard-vs-soft distinction.
 *
 * Consumed by the duck-screen bead (asn-3sm) for its animation state, and by
 * [com.montauk.voicecapture.ui.RecordingScreen] for the pause button's label
 * (bead asn-o63 dropped the separate auto-pause banner that used to also
 * read this -- the button is the only pause UI now).
 */
enum class RecordingActivityState {
    /** VAD currently reads above the speech-energy threshold. */
    SPEAKING,

    /** VAD currently reads below the speech-energy threshold, but not yet long enough to auto-pause. */
    QUIET,

    /**
     * Soft pause (bead asn-r60): mic stays open, a rolling ring buffer keeps
     * the trailing few seconds of audio, nothing is persisted or sent to STT
     * meanwhile. Auto-resumes to [SPEAKING] the moment VAD detects speech.
     */
    AUTO_PAUSED,

    /**
     * Hard pause (bead asn-r60): explicit user tap. No audio persisted, no
     * buffer retained, the STT stream is closed outright. Only an explicit
     * resume tap leaves this state -- VAD never auto-resumes out of it.
     */
    USER_PAUSED,
}

/**
 * Publishes [RecordingActivityState] as its own dedicated `StateFlow`,
 * mirroring [RecordingStateHolder]/[TranscriptStateHolder]'s
 * no-bound-service-connection-required pattern. Deliberately a separate
 * holder rather than a field folded into [RecordingUiState] or
 * [TranscriptUiState] -- the bead asks for "a clean StateFlow" the
 * duck-animation consumer can collect on its own, independent of either
 * existing state blob's shape or update cadence.
 */
object RecordingActivityStateHolder {
    private val _state = MutableStateFlow(RecordingActivityState.QUIET)
    val state: StateFlow<RecordingActivityState> = _state.asStateFlow()

    fun set(value: RecordingActivityState) {
        _state.value = value
    }

    fun update(transform: (RecordingActivityState) -> RecordingActivityState) {
        _state.value = transform(_state.value)
    }

    /** Call at the start of every new recording session so a previous session's state never leaks in. */
    fun reset() {
        _state.value = RecordingActivityState.QUIET
    }
}
