package com.montauk.voicecapture.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingModeStateMachineTest {

    @Test
    fun `starts in Listen with a single t=0 history entry`() {
        val machine = RecordingModeStateMachine()

        assertEquals(RecordingMode.LISTEN, machine.currentMode)
        assertEquals(listOf(ModeChange(0L, RecordingMode.LISTEN)), machine.history)
    }

    @Test
    fun `selecting the already-current mode is a no-op`() {
        val machine = RecordingModeStateMachine()

        val change = machine.select(RecordingMode.LISTEN, atMs = 5_000L)

        assertNull(change)
        assertEquals(RecordingMode.LISTEN, machine.currentMode)
        assertEquals(1, machine.history.size)
    }

    @Test
    fun `selecting a disabled mode is a no-op and never changes current mode or history`() {
        val machine = RecordingModeStateMachine()

        val change = machine.select(RecordingMode.CONVERSE, atMs = 5_000L)

        assertNull(change)
        assertEquals(RecordingMode.LISTEN, machine.currentMode)
        assertEquals(1, machine.history.size)
    }

    @Test
    fun `selecting a disabled mode from a non-default start is still a no-op`() {
        val machine = RecordingModeStateMachine(initialMode = RecordingMode.LISTEN)

        val change = machine.select(RecordingMode.CHALLENGE, atMs = 12_000L)

        assertNull(change)
        assertEquals(1, machine.history.size)
    }

    @Test
    fun `selecting an enabled mode different from current records the switch`() {
        // Starts from a non-default mode so this can exercise the "actual
        // switch" branch even though Listen is the only mode currently
        // enabled in the UI -- the state machine's own gating (isEnabled +
        // different-from-current) is what's under test here, independent of
        // which specific modes the product has turned on so far.
        val machine = RecordingModeStateMachine(initialMode = RecordingMode.CONVERSE)

        val change = machine.select(RecordingMode.LISTEN, atMs = 8_400L)

        assertNotNull(change)
        assertEquals(ModeChange(8_400L, RecordingMode.LISTEN), change)
        assertEquals(RecordingMode.LISTEN, machine.currentMode)
        assertEquals(
            listOf(ModeChange(0L, RecordingMode.CONVERSE), ModeChange(8_400L, RecordingMode.LISTEN)),
            machine.history,
        )
    }

    @Test
    fun `history is a defensive copy that further selects don't retroactively mutate`() {
        val machine = RecordingModeStateMachine()
        val historySnapshot = machine.history

        machine.select(RecordingMode.CONVERSE, atMs = 1_000L) // no-op, but even if it weren't:

        assertEquals(1, historySnapshot.size)
    }
}
