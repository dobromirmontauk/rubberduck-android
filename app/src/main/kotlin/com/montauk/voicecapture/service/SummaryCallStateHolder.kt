package com.montauk.voicecapture.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * True for exactly the span of an in-flight [com.montauk.voicecapture.summary.SummaryCoordinator]
 * round's real network call (bead asn-3sm's duck-screen THINK state: "summary
 * call in flight ... for the duration of the call") -- deliberately a
 * separate, dedicated `StateFlow` rather than a field folded into
 * [SummaryUiState], same reasoning [RecordingActivityStateHolder]'s own KDoc
 * gives for its own split: this flips far more often (every eligible tick)
 * and far more briefly than [SummaryUiState] itself changes (only when a
 * round actually adds/staleness-flips something), so collapsing the two
 * would make every duck-screen recomposition also re-derive unrelated
 * summary-content state.
 *
 * [RecordingService] is the only writer, via [SummaryCoordinator.onTick]'s
 * `onCallStarted` callback (set true immediately before the real
 * generator call, reset false right after `onTick` returns regardless of
 * outcome) -- [com.montauk.voicecapture.ui.RecordingScreen] reads [state]
 * without a bound-service connection, same no-bound-service pattern as
 * every other `*StateHolder` in this package.
 */
object SummaryCallStateHolder {
    private val _state = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _state.asStateFlow()

    fun set(inFlight: Boolean) {
        _state.value = inFlight
    }

    /** Call at the start of every new recording session so a previous session's in-flight flag never leaks in. */
    fun reset() {
        _state.value = false
    }
}
