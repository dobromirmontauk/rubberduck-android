package com.montauk.voicecapture.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The duck's live bullet summary (bead asn-3sm/asn-evl, design-board
 * sections 2 & 4): [bullets] is the current append-only list ("existing
 * bullets stay stable unless truly wrong" per the design board), [newestIndex]
 * is which one just arrived (drives the notes card's duck-yellow highlight;
 * null when there's nothing new to highlight, e.g. right after [reset]),
 * [updatedAtMs] is when this snapshot landed.
 *
 * **Shared interface (bead asn-3sm defines it, bead asn-evl implements the
 * producer).** [com.montauk.voicecapture.duck.NotesCard] is built and
 * choreographed against [SummaryStateHolder.state] with a fake/preview
 * feed; asn-evl's summary engine (a `SummaryEngine`/coordinator, roughly
 * mirroring how [com.montauk.voicecapture.tags.TagCoordinator] feeds
 * [TagsStateHolder]) is expected to call [SummaryStateHolder.update] every
 * ~60s with a fresh snapshot -- this file is intentionally the entire
 * contract, kept tiny so both sides can build against it independently.
 */
data class SummaryUiState(
    val bullets: List<String> = emptyList(),
    val newestIndex: Int? = null,
    val updatedAtMs: Long = 0L,
)

/** Publishes [SummaryUiState], mirroring [TagsStateHolder]/[TranscriptStateHolder]'s no-bound-service pattern. */
object SummaryStateHolder {
    private val _state = MutableStateFlow(SummaryUiState())
    val state: StateFlow<SummaryUiState> = _state.asStateFlow()

    fun update(newState: SummaryUiState) {
        _state.value = newState
    }

    /** Call at the start of every new recording session so a previous session's bullets never leak in. */
    fun reset() {
        _state.value = SummaryUiState()
    }
}
