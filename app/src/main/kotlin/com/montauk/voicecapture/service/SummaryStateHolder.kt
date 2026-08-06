package com.montauk.voicecapture.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live rolling bullet-point summary's current UI-facing state (bead
 * asn-evl) -- shared contract between [com.montauk.voicecapture.summary.SummaryCoordinator]
 * (pipeline half, owned by [RecordingService]) and the notepad-card UI (duck
 * screen half). Mirrors [TagsStateHolder]'s shape/placement exactly.
 */
data class SummaryUiState(
    /** The full bullet list, oldest first. */
    val bullets: List<String> = emptyList(),
    /** Index into [bullets] of the bullet the card should highlight as new this round -- null if no round has landed yet, or the last round didn't add anything (e.g. a stale-only round). */
    val newestIndex: Int? = null,
    /** Recording-elapsed ms of the round that produced this state. */
    val updatedAtMs: Long = 0L,
    /** True when the last round's API call failed and [bullets] are held over from an earlier, successful round -- the card should show a "notes paused" style indicator rather than hide anything. */
    val stale: Boolean = false,
)

/**
 * Publishes [SummaryUiState] to the UI, mirroring [TagsStateHolder] /
 * [TranscriptStateHolder] -- owned by [RecordingService] (via
 * [com.montauk.voicecapture.summary.SummaryCoordinator]), read by the
 * recording screen without a bound service connection.
 */
object SummaryStateHolder {
    private val _state = MutableStateFlow(SummaryUiState())
    val state: StateFlow<SummaryUiState> = _state.asStateFlow()

    fun update(state: SummaryUiState) {
        _state.value = state
    }

    fun reset() {
        _state.value = SummaryUiState()
    }
}
