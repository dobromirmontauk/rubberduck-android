package com.montauk.voicecapture.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead asn-l34: the failing-first test for the root cause of the recording
 * elapsed-counter freeze -- [RecordingService.startTicker]'s per-tick body
 * used to run unguarded directly inside its `while` loop, so any one throw
 * (e.g. `NotificationManager.notify()` mid-session) permanently ended the
 * loop and froze the elapsed clock for the rest of the recording. These
 * tests exercise [runResilientTicker] directly, without any Android/
 * Robolectric dependency, since it's the loop's entire exception-isolation
 * contract -- [RecordingService] itself just supplies the tick body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResilientTickerTest {

    @Test
    fun `a tick that throws does not stop later ticks`() = runTest {
        var tickCount = 0
        val failures = mutableListOf<Throwable>()
        var keepGoing = true

        val job = launch {
            runResilientTicker(
                scope = this,
                intervalMs = 1_000L,
                continueCondition = { keepGoing },
                onTickFailure = { failures += it },
            ) {
                tickCount++
                if (tickCount == 2) error("boom on tick 2")
            }
        }

        advanceTimeBy(5_500L) // five ticks' worth
        keepGoing = false
        job.join()

        assertEquals("exactly the one injected failure should have been reported", 1, failures.size)
        assertTrue(
            "ticks after the failing one must still have run -- saw only $tickCount",
            tickCount >= 5,
        )
    }

    @Test
    fun `every tick throwing still keeps the loop alive until continueCondition flips`() = runTest {
        var tickCount = 0
        val failures = mutableListOf<Throwable>()
        var keepGoing = true

        val job = launch {
            runResilientTicker(
                scope = this,
                intervalMs = 1_000L,
                continueCondition = { keepGoing },
                onTickFailure = { failures += it },
            ) {
                tickCount++
                error("always fails")
            }
        }

        advanceTimeBy(3_500L)
        keepGoing = false
        job.join()

        assertTrue("loop must keep running a tick every interval even if every one fails", tickCount >= 3)
        assertEquals(tickCount, failures.size)
    }

    @Test
    fun `a healthy tick body never reports a failure`() = runTest {
        var tickCount = 0
        val failures = mutableListOf<Throwable>()
        var keepGoing = true

        val job = launch {
            runResilientTicker(
                scope = this,
                intervalMs = 1_000L,
                continueCondition = { keepGoing },
                onTickFailure = { failures += it },
            ) {
                tickCount++
            }
        }

        advanceTimeBy(3_500L)
        keepGoing = false
        job.join()

        assertTrue(tickCount >= 3)
        assertTrue("no tick threw, so no failure should have been reported", failures.isEmpty())
    }
}
