package com.montauk.voicecapture.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead asn-638: [PendingRemovalCountdown.run]'s own timing, resolved with
 * `runTest`'s virtual clock (see the class KDoc for why it's a plain
 * coroutine, not a Compose animation) -- mirrors
 * [com.montauk.voicecapture.service.TooShortDecisionTest]'s approach so
 * asserting the full [PENDING_REMOVAL_WINDOW_MS] default (10s) elapsed never
 * needs a real 10s wait here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingRemovalCountdownTest {

    @Test
    fun `onElapsed fires once the full window has elapsed`() = runTest {
        var elapsedFired = false
        PendingRemovalCountdown.run(windowMs = 200L, onProgress = {}, onElapsed = { elapsedFired = true })

        assertTrue("onElapsed must fire once run() returns normally", elapsedFired)
    }

    @Test
    fun `onProgress starts at 1f and reaches 0f by the time onElapsed fires`() = runTest {
        val progressValues = mutableListOf<Float>()
        PendingRemovalCountdown.run(windowMs = 200L, onProgress = { progressValues.add(it) }, onElapsed = {})

        assertEquals(1f, progressValues.first())
        assertEquals(0f, progressValues.last())
        // Monotonically shrinking -- a real countdown, not a flat line or a jump straight to 0.
        assertTrue(
            "progress must never tick back up",
            progressValues.zipWithNext().all { (earlier, later) -> later <= earlier },
        )
    }

    @Test
    fun `the default PENDING_REMOVAL_WINDOW_MS resolves without a real 10s wait`() = runTest {
        var elapsedFired = false
        PendingRemovalCountdown.run(onProgress = {}, onElapsed = { elapsedFired = true })

        assertTrue(elapsedFired)
    }

    @Test
    fun `cancelling before the window elapses, as Undo does, never calls onElapsed`() = runTest {
        var elapsedFired = false
        val job = launch {
            PendingRemovalCountdown.run(windowMs = 10_000L, onProgress = {}, onElapsed = { elapsedFired = true })
        }

        advanceTimeBy(1_000L)
        runCurrent()
        job.cancel()

        assertFalse("cancelling mid-countdown (Undo, or the row leaving composition) must never let the commit fire", elapsedFired)
    }
}
