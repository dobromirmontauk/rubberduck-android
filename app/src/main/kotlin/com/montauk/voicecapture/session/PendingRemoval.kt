package com.montauk.voicecapture.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which swipe direction produced a [PendingRemoval] (bead vn-edu.67) -- drives the snackbar's message text. */
enum class RemovalAction { DELETE, ARCHIVE }

/** UI-facing snapshot of a deferred removal, published on [PendingRemovalHolder.state]. */
data class PendingRemoval(val sessionId: String, val action: RemovalAction, val message: String)

/**
 * Bead vn-edu.67: Gmail-style deferred deletion behind the Sessions screen's
 * swipe-to-delete/archive undo snackbar. A swipe that's instant-eligible
 * (uploaded/integrated delete; integrated archive) removes the row from the
 * UI immediately but does NOT touch disk yet -- [SessionListScreen] calls
 * [schedule] with the real removal ([execute], typically wrapping
 * [SessionStore.deleteSession]) deferred until [flush] actually runs it.
 * [flush] is invoked from three places, all outside this object: the
 * Undo snackbar's own timeout elapsing, the snackbar being dismissed, or the
 * app backgrounding (see `AppNavHost`) -- this object only holds the
 * schedule/undo/flush *state machine*, deliberately with no coroutines or
 * timers of its own, so it's plain-JVM unit-testable (no Robolectric, no
 * virtual-time `runTest`) purely by calling these three methods in order and
 * asserting when [execute] did or didn't run.
 *
 * Exactly one removal is ever pending at a time, Gmail's own behavior when a
 * second swipe happens before the first's undo window closes: [schedule]
 * flushes (commits) whatever was already pending before registering the new
 * one, rather than stacking multiple undo windows.
 */
object PendingRemovalHolder {
    private val _state = MutableStateFlow<PendingRemoval?>(null)
    val state: StateFlow<PendingRemoval?> = _state.asStateFlow()

    private var pendingExecute: (() -> Unit)? = null

    /**
     * Registers [removal], deferring [execute] until a later [flush] --
     * never calls [execute] itself. Any removal already pending is flushed
     * (its own [execute] runs immediately) first, so callers never need to
     * flush explicitly before scheduling the next one.
     */
    fun schedule(removal: PendingRemoval, execute: () -> Unit) {
        flush()
        pendingExecute = execute
        _state.value = removal
    }

    /**
     * The user tapped Undo: clears the pending removal without ever calling
     * its [execute] -- the on-disk session this was scheduled for is left
     * completely untouched.
     */
    fun undo() {
        pendingExecute = null
        _state.value = null
    }

    /**
     * Commits whatever removal is currently pending by calling its
     * [execute], then clears the pending state. A no-op when nothing is
     * pending, so every caller (undo-window timeout, snackbar dismissal, app
     * backgrounding, or [schedule] flushing its predecessor) can call this
     * unconditionally.
     */
    fun flush() {
        val execute = pendingExecute ?: return
        pendingExecute = null
        _state.value = null
        execute()
    }
}
