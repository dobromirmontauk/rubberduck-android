package com.montauk.voicecapture.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which swipe direction produced a [PendingRemoval] (bead vn-edu.67) -- drives the pending row's message/tint. */
enum class RemovalAction { DELETE, ARCHIVE }

/** UI-facing snapshot of a deferred removal, published (keyed by [sessionId]) on [PendingRemovalHolder.state]. */
data class PendingRemoval(val sessionId: String, val action: RemovalAction, val message: String)

/**
 * Bead vn-edu.67 (extended by asn-638): Gmail-style deferred deletion behind
 * the Sessions screen's inline swipe-to-delete/archive Undo affordance. A
 * swipe that's instant-eligible (uploaded/integrated delete; integrated
 * archive) does NOT touch disk yet -- [SessionListScreen] calls [schedule]
 * with the real removal ([execute], typically wrapping
 * [SessionStore.deleteSession]) deferred until [flush]/[flushAll] actually
 * run it.
 *
 * asn-638 replaced the single-slot, Gmail-"one at a time" model with a
 * map keyed by [PendingRemoval.sessionId]: the swiped row now stays in place
 * showing its own inline countdown + Undo button (rather than a single
 * shared snackbar elsewhere), so multiple rows can be pending at once, each
 * on its own independent 10s clock -- scheduling a second removal no longer
 * flushes the first.
 *
 * [flush]/[flushAll] are invoked from outside this object: a row's own
 * countdown elapsing ([flush]), the Sessions screen being left or the app
 * backgrounding ([flushAll], see `AppNavHost` and `SessionListScreen`).
 * This object deliberately owns no coroutines or timers of its own -- just
 * the schedule/undo/flush *state machine* -- so it's plain-JVM
 * unit-testable (no Robolectric, no virtual-time `runTest`) purely by
 * calling these methods in order and asserting when [execute] did or didn't
 * run.
 */
object PendingRemovalHolder {
    private val _state = MutableStateFlow<Map<String, PendingRemoval>>(emptyMap())
    val state: StateFlow<Map<String, PendingRemoval>> = _state.asStateFlow()

    private val pendingExecutes = mutableMapOf<String, () -> Unit>()

    /**
     * Registers [removal], deferring [execute] until a later [flush] or
     * [flushAll] -- never calls [execute] itself. Any removal already
     * pending for a *different* session keeps running its own clock
     * untouched; re-scheduling the *same* [PendingRemoval.sessionId] (e.g. a
     * second swipe before the first commits) simply replaces it, discarding
     * the earlier `execute` without ever calling it.
     */
    fun schedule(removal: PendingRemoval, execute: () -> Unit) {
        pendingExecutes[removal.sessionId] = execute
        _state.value = _state.value + (removal.sessionId to removal)
    }

    /**
     * The user tapped Undo on [sessionId]'s row: clears its pending removal
     * without ever calling its [execute] -- the on-disk session this was
     * scheduled for is left completely untouched. A no-op if [sessionId]
     * isn't pending.
     */
    fun undo(sessionId: String) {
        pendingExecutes.remove(sessionId)
        _state.value = _state.value - sessionId
    }

    /**
     * Commits the removal pending for [sessionId] by calling its [execute],
     * then clears it. A no-op when [sessionId] isn't pending, so every
     * caller (a row's own countdown elapsing) can call this unconditionally.
     */
    fun flush(sessionId: String) {
        val execute = pendingExecutes.remove(sessionId) ?: return
        _state.value = _state.value - sessionId
        execute()
    }

    /**
     * Commits every currently-pending removal -- the "leaving the screen" /
     * "app backgrounded" leg of asn-638's no-lost-commits guarantee, since
     * neither of those can rely on each row's own Compose-side countdown
     * coroutine surviving to fire [flush] itself. A no-op when nothing is
     * pending.
     */
    fun flushAll() {
        if (pendingExecutes.isEmpty()) return
        val toRun = pendingExecutes.values.toList()
        pendingExecutes.clear()
        _state.value = emptyMap()
        toRun.forEach { it() }
    }
}
