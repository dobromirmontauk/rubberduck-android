package com.montauk.voicecapture.service

import com.montauk.voicecapture.tags.TagTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the vault's currently-resolved [TagTree] (bead vn-edu.47's
 * fetch/cache/daily-refresh policy, via [com.montauk.voicecapture.VoiceCaptureApp.currentTagTree])
 * to the UI, mirroring [TagsStateHolder]/[TagRailStateHolder]'s no-bound-
 * service pattern.
 *
 * Bead asn-45m: [com.montauk.voicecapture.ui.RecordingScreen] needs the tree
 * to resolve tag_id -> path for the filing-destination ribbon and to power
 * the tag-tree picker's search results, but deliberately never calls
 * [com.montauk.voicecapture.VoiceCaptureApp.currentTagTree] itself --
 * that's a real network-capable call, and every Compose test that renders
 * [com.montauk.voicecapture.ui.RecordingScreen] would otherwise risk making
 * one. Only [com.montauk.voicecapture.service.RecordingService] (an active
 * recording session) ever calls it, exactly once per session in
 * [com.montauk.voicecapture.service.RecordingService.beginRecording], and
 * publishes the result here; the UI just reads whatever's here, defaulting
 * to [TagTree.EMPTY] (the same "no vault"/free-form fallback every other
 * keyless path in this app already uses) until that resolves.
 */
object TagTreeStateHolder {
    private val _state = MutableStateFlow(TagTree.EMPTY)
    val state: StateFlow<TagTree> = _state.asStateFlow()

    fun update(tree: TagTree) {
        _state.value = tree
    }

    fun reset() {
        _state.value = TagTree.EMPTY
    }
}
