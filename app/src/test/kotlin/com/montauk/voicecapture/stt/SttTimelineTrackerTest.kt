package com.montauk.voicecapture.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class SttTimelineTrackerTest {

    @Test
    fun `with no reconnect, toSessionMs is the identity`() {
        val tracker = SttTimelineTracker()

        assertEquals(1_500L, tracker.toSessionMs(1_500L))
    }

    @Test
    fun `onReconnect offsets subsequent timestamps by the last recorded raw endMs`() {
        val tracker = SttTimelineTracker()
        tracker.recordRawEndMs(4_000L)

        tracker.onReconnect()

        assertEquals(4_000L, tracker.toSessionMs(0L))
        assertEquals(4_500L, tracker.toSessionMs(500L))
    }

    @Test
    fun `timestamps stay monotonic across two reconnects`() {
        val tracker = SttTimelineTracker()

        // First connection: turns up to 4_000ms.
        tracker.recordRawEndMs(4_000L)
        tracker.onReconnect()

        // Second connection resets to ~0 again, but is now offset by 4_000.
        tracker.recordRawEndMs(2_000L)
        val secondConnectionEnd = tracker.toSessionMs(2_000L)
        assertEquals(6_000L, secondConnectionEnd)

        tracker.onReconnect()

        // Third connection starts fresh at raw 0 again, offset by 4_000 + 2_000.
        assertEquals(6_000L, tracker.toSessionMs(0L))
    }

    @Test
    fun `recordRawEndMs tracks the maximum seen, not just the last call`() {
        val tracker = SttTimelineTracker()
        tracker.recordRawEndMs(3_000L)
        tracker.recordRawEndMs(1_000L) // an out-of-order/smaller call must not regress the offset

        tracker.onReconnect()

        assertEquals(3_000L, tracker.toSessionMs(0L))
    }

    @Test
    fun `reset clears the accumulated offset`() {
        val tracker = SttTimelineTracker()
        tracker.recordRawEndMs(5_000L)
        tracker.onReconnect()

        tracker.reset()

        assertEquals(100L, tracker.toSessionMs(100L))
    }
}
