package com.montauk.voicecapture.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bead vn-edu.67: one-shot transient snackbar text for a swipe gesture that
 * settles back without acting -- today, only the right-swipe (archive)
 * attempt on a session that isn't yet [SessionStatus.INTEGRATED]. Separate
 * from [PendingRemovalHolder] since a hint has no undo/execute semantics at
 * all: nothing was removed, there's nothing to commit or restore, just a
 * message to show once. Mirrors
 * [com.montauk.voicecapture.service.TooShortWarningStateHolder]'s minimal
 * publish/clear shape.
 */
object SwipeHintStateHolder {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun show(text: String) {
        _message.value = text
    }

    fun clear() {
        _message.value = null
    }
}
