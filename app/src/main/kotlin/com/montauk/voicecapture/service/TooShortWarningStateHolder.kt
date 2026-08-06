package com.montauk.voicecapture.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-facing snapshot of a pending too-short-session warning (bead vn-edu.56). */
data class TooShortWarningUiState(val sessionId: String)

/**
 * Bridges [RecordingService]'s post-Stop too-short decision window
 * ([TooShortDecision]) to the UI, mirroring [RecordingStateHolder]'s
 * no-bound-service pattern: the service calls [beginWarning] when a stopped
 * session trips [TooShortPolicy], publishing a non-null [state] for up to
 * [TooShortPolicy.WARNING_WINDOW_MS] (or until [saveAnyway] is called). The
 * UI observes [state] to show the "Session too short to save" snackbar with
 * a "Save anyway" action wired to [saveAnyway] -- hosted high enough in the
 * nav tree (the Scaffold-level snackbar host in `AppNavHost`, not
 * `RecordingScreen` itself) that it survives the Stop->Sessions navigation
 * that happens immediately on tapping Stop.
 */
object TooShortWarningStateHolder {
    private val _state = MutableStateFlow<TooShortWarningUiState?>(null)
    val state: StateFlow<TooShortWarningUiState?> = _state.asStateFlow()

    /** Non-null only while a decision is pending. */
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    /**
     * Starts a new pending decision for [sessionId], replacing any prior one
     * (sessions never overlap in practice -- [RecordingService] only ever
     * has one in flight). Returns the [CompletableDeferred] the caller should
     * await (with a timeout) for the resolution -- see [TooShortDecision.resolve].
     */
    fun beginWarning(sessionId: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pendingDecision = deferred
        _state.value = TooShortWarningUiState(sessionId)
        return deferred
    }

    /** Called by the service once the decision (save-anyway or timeout-discard) is resolved, to clear the UI. */
    fun clearWarning() {
        pendingDecision = null
        _state.value = null
    }

    /** UI hook: the user tapped "Save anyway" on the snackbar. No-op if there's no pending decision (e.g. it already timed out). */
    fun saveAnyway() {
        pendingDecision?.complete(true)
    }
}
