package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // Bead asn-dp2 v2: the write-pose-active window (enter/hold/leave).

    @Test
    fun `write pose is inactive before the card has ever shown`() {
        val choreographer = NotesCardChoreographer()

        assertFalse(choreographer.isWritePoseActive(0L))
        assertFalse(choreographer.isWritePoseActive(1_000_000L))
    }

    @Test
    fun `write pose is active the instant the card is revealed`() {
        val choreographer = NotesCardChoreographer()

        choreographer.onSummaryUpdated(1_000L)

        assertTrue(choreographer.isWritePoseActive(1_000L))
    }

    @Test
    fun `write pose stays active for the whole SHOWN hold`() {
        val choreographer = NotesCardChoreographer(autoHideAfterMs = 5_000L)
        choreographer.onSummaryUpdated(1_000L)

        choreographer.tick(5_999L)

        assertTrue(choreographer.isWritePoseActive(5_999L))
    }

    @Test
    fun `write pose stays active for EXIT_ANIMATION_MS after auto-hide, then drops`() {
        val choreographer = NotesCardChoreographer(autoHideAfterMs = 5_000L)
        choreographer.onSummaryUpdated(1_000L)
        choreographer.tick(6_000L) // auto-hide fires, mode -> HIDDEN
        assertEquals(NotesCardMode.HIDDEN, choreographer.mode)

        assertTrue(
            "the exit slide+fade is still playing -- the duck must hold his write pose through it",
            choreographer.isWritePoseActive(6_000L + NotesCardChoreographer.EXIT_ANIMATION_MS - 1),
        )
        assertFalse(choreographer.isWritePoseActive(6_000L + NotesCardChoreographer.EXIT_ANIMATION_MS))
    }

    @Test
    fun `write pose stays active through the exit tail after an un-pin dismissal too`() {
        val choreographer = NotesCardChoreographer()
        choreographer.onSummaryUpdated(1_000L)
        choreographer.togglePin(1_100L)

        choreographer.togglePin(1_200L) // un-pin -- also hides via NotesCardChoreographer's shared hide()

        assertTrue(choreographer.isWritePoseActive(1_200L))
        assertFalse(choreographer.isWritePoseActive(1_200L + NotesCardChoreographer.EXIT_ANIMATION_MS))
    }

    @Test
    fun `a fresh reveal while the previous exit tail is still playing re-activates write pose immediately`() {
        val choreographer = NotesCardChoreographer(autoHideAfterMs = 5_000L)
        choreographer.onSummaryUpdated(1_000L)
        choreographer.tick(6_000L) // auto-hide -> HIDDEN, exit tail now playing

        choreographer.onSummaryUpdated(6_100L) // a new round lands mid-exit-tail

        assertEquals(NotesCardMode.SHOWN, choreographer.mode)
        assertTrue(choreographer.isWritePoseActive(6_100L))
    }
}
