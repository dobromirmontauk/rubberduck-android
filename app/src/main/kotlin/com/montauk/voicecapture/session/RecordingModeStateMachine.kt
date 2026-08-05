package com.montauk.voicecapture.session

/** One entry in a session's mode history: [tMs] is session-elapsed milliseconds. */
data class ModeChange(val tMs: Long, val mode: RecordingMode)

/**
 * Tracks which [RecordingMode] a recording session is in and the full
 * history of mode changes, in session-elapsed milliseconds. The initial mode
 * is recorded as a change at t=0 immediately on construction -- per the mode
 * switcher spec, "every mode change (including the initial mode at session
 * start)" gets an entry, so the caller doesn't need a separate code path for
 * the first one.
 *
 * [select] only ever switches to a [RecordingMode.isEnabled] mode. Converse
 * and Challenge are visible-but-disabled in the UI (mode switcher on the
 * recording screen never even calls [select] for them -- see
 * [com.montauk.voicecapture.ui.RecordingScreen]), but the rule is enforced
 * here too so a bad request can never corrupt session state.
 */
class RecordingModeStateMachine(initialMode: RecordingMode = RecordingMode.DEFAULT) {
    private val _history = mutableListOf(ModeChange(0L, initialMode))

    var currentMode: RecordingMode = initialMode
        private set

    val history: List<ModeChange> get() = _history.toList()

    /**
     * Attempts to switch to [mode] at [atMs]. Returns the resulting
     * [ModeChange] if the switch actually happened, or null for a no-op --
     * either [mode] is disabled, or it's already the current mode (e.g.
     * selecting Listen while already in Listen).
     */
    fun select(mode: RecordingMode, atMs: Long): ModeChange? {
        if (!mode.isEnabled || mode == currentMode) return null
        currentMode = mode
        val change = ModeChange(atMs, mode)
        _history.add(change)
        return change
    }
}
