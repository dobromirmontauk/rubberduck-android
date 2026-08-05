package com.montauk.voicecapture.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFollowStateTest {

    @Test
    fun `follows by default before any manual scroll`() {
        val state = TranscriptFollowState(idleMs = 3_000L)
        assertTrue(state.onNewContent(nowMs = 0L))
        assertTrue(state.onNewContent(nowMs = 10_000L))
    }

    @Test
    fun `manual scroll away from bottom stops following`() {
        val state = TranscriptFollowState(idleMs = 3_000L)
        state.onManualScroll(nowMs = 1_000L, isAtBottom = false)
        assertFalse(state.onNewContent(nowMs = 1_100L))
    }

    @Test
    fun `new content within idle window does not resume following`() {
        val state = TranscriptFollowState(idleMs = 3_000L)
        state.onManualScroll(nowMs = 1_000L, isAtBottom = false)
        assertFalse(state.onNewContent(nowMs = 3_999L))
    }

    @Test
    fun `new content after idle window resumes following`() {
        val state = TranscriptFollowState(idleMs = 3_000L)
        state.onManualScroll(nowMs = 1_000L, isAtBottom = false)
        assertTrue(state.onNewContent(nowMs = 4_000L))
    }

    @Test
    fun `once idle elapses following stays true for later content without scrolling away again`() {
        val state = TranscriptFollowState(idleMs = 3_000L)
        state.onManualScroll(nowMs = 1_000L, isAtBottom = false)
        assertTrue(state.onNewContent(nowMs = 4_000L))
        assertTrue(state.onNewContent(nowMs = 100_000L))
    }

    @Test
    fun `manual scroll back to bottom resumes following immediately`() {
        val state = TranscriptFollowState(idleMs = 3_000L)
        state.onManualScroll(nowMs = 1_000L, isAtBottom = false)
        state.onManualScroll(nowMs = 1_500L, isAtBottom = true)
        assertTrue(state.onNewContent(nowMs = 1_600L))
    }

    @Test
    fun `repeated manual scroll-away resets the idle clock`() {
        val state = TranscriptFollowState(idleMs = 3_000L)
        state.onManualScroll(nowMs = 1_000L, isAtBottom = false)
        // Reader nudges the list again at 3_500ms, well within the first idle
        // window but restarting the clock -- 6_000ms is only 2_500ms after
        // this second scroll-away, so following must still be suppressed.
        state.onManualScroll(nowMs = 3_500L, isAtBottom = false)
        assertFalse(state.onNewContent(nowMs = 6_000L))
        assertTrue(state.onNewContent(nowMs = 6_500L))
    }
}
