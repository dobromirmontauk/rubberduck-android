package com.montauk.voicecapture.service

import com.montauk.voicecapture.tags.DisplayedTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the current up-to-3 displayed tags (bead vn-edu.38) to the UI,
 * mirroring [TranscriptStateHolder] / [RecordingStateHolder] -- owned by
 * [RecordingService] (via [com.montauk.voicecapture.tags.TagCoordinator]),
 * read by [com.montauk.voicecapture.ui.RecordingScreen] without a bound
 * service connection.
 */
object TagsStateHolder {
    private val _state = MutableStateFlow<List<DisplayedTag>>(emptyList())
    val state: StateFlow<List<DisplayedTag>> = _state.asStateFlow()

    fun update(tags: List<DisplayedTag>) {
        _state.value = tags
    }

    fun reset() {
        _state.value = emptyList()
    }
}
