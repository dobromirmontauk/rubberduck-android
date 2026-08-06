package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Test

class NotesCardChoreographerTest {

    @Test
    fun `starts HIDDEN`() {
        assertEquals(NotesCardMode.HIDDEN, NotesCardChoreographer().mode)
    }

    @Test
    fun `a fresh summary reveals SHOWN`() {
        val choreographer = NotesCardChoreographer()
        choreographer.onSummaryUpdated(1_000L)
        assertEquals(NotesCardMode.SHOWN, choreographer.mode)
    }

    @Test
    fun `SHOWN auto-hides after its window`() {
        val choreographer = NotesCardChoreographer(autoHideAfterMs = 5_000L)
        choreographer.onSummaryUpdated(1_000L)
        choreographer.tick(5_999L)
        assertEquals(NotesCardMode.SHOWN, choreographer.mode)
        choreographer.tick(6_000L)
        assertEquals(NotesCardMode.HIDDEN, choreographer.mode)
    }

    @Test
    fun `tapping while SHOWN pins it open`() {
        val choreographer = NotesCardChoreographer(autoHideAfterMs = 5_000L)
        choreographer.onSummaryUpdated(1_000L)
        choreographer.togglePin(2_000L)
        assertEquals(NotesCardMode.PINNED, choreographer.mode)

        // A PINNED card must not auto-hide even past the window.
        choreographer.tick(10_000L)
        assertEquals(NotesCardMode.PINNED, choreographer.mode)
    }

    @Test
    fun `tapping while PINNED un-pins (hides) it`() {
        val choreographer = NotesCardChoreographer()
        choreographer.onSummaryUpdated(1_000L)
        choreographer.togglePin(2_000L)
        choreographer.togglePin(3_000L)
        assertEquals(NotesCardMode.HIDDEN, choreographer.mode)
    }

    @Test
    fun `a new summary while PINNED does not interrupt the pin`() {
        val choreographer = NotesCardChoreographer()
        choreographer.onSummaryUpdated(1_000L)
        choreographer.togglePin(2_000L)
        choreographer.onSummaryUpdated(3_000L)
        assertEquals(NotesCardMode.PINNED, choreographer.mode)
    }

    @Test
    fun `peek reveals SHOWN from HIDDEN and still auto-hides on its own window`() {
        val choreographer = NotesCardChoreographer(autoHideAfterMs = 5_000L)
        choreographer.peek(1_000L)
        assertEquals(NotesCardMode.SHOWN, choreographer.mode)
        choreographer.tick(6_000L)
        assertEquals(NotesCardMode.HIDDEN, choreographer.mode)
    }

    @Test
    fun `peek is a no-op while already SHOWN or PINNED`() {
        val choreographer = NotesCardChoreographer(autoHideAfterMs = 5_000L)
        choreographer.onSummaryUpdated(1_000L)
        choreographer.peek(1_500L) // should not reset the auto-hide window
        choreographer.tick(5_999L)
        assertEquals(NotesCardMode.SHOWN, choreographer.mode)
        choreographer.tick(6_000L)
        assertEquals(NotesCardMode.HIDDEN, choreographer.mode)
    }
}
