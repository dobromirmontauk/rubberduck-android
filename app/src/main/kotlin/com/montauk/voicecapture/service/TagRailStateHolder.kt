package com.montauk.voicecapture.service

import com.montauk.voicecapture.tags.TagRailChip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the recording screen's editable tag rail's current chip list
 * (bead asn-45m) to the UI, mirroring [TagsStateHolder]'s no-bound-service
 * pattern: [com.montauk.voicecapture.service.RecordingService] owns the
 * actual [com.montauk.voicecapture.tags.TagChipRail] state machine and calls
 * [update] here every time a suggested-tags change or a user add/remove/swap
 * changes its merged [chips][com.montauk.voicecapture.tags.TagChipRail.chips]
 * output; [com.montauk.voicecapture.ui.RecordingScreen] reads [state] without
 * needing a bound service connection.
 */
object TagRailStateHolder {
    private val _state = MutableStateFlow<List<TagRailChip>>(emptyList())
    val state: StateFlow<List<TagRailChip>> = _state.asStateFlow()

    fun update(chips: List<TagRailChip>) {
        _state.value = chips
    }

    fun reset() {
        _state.value = emptyList()
    }
}
