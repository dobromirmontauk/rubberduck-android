package com.montauk.voicecapture.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausableElapsedClockTest {

    @Test
    fun `with no pause, elapsedMs simply mirrors rawElapsedMs`() {
        val clock = PausableElapsedClock()

        assertEquals(5_000L, clock.elapsedMs(nowMs = 5_000L, rawElapsedMs = 5_000L))
    }

    @Test
    fun `elapsedMs holds steady while paused even as rawElapsedMs grows`() {
        val clock = PausableElapsedClock()
        clock.pause(nowMs = 10_000L)

        val duringPause1 = clock.elapsedMs(nowMs = 12_000L, rawElapsedMs = 12_000L)
        val duringPause2 = clock.elapsedMs(nowMs = 15_000L, rawElapsedMs = 15_000L)

        assertEquals(10_000L, duringPause1)
        assertEquals(10_000L, duringPause2)
    }

    @Test
    fun `resume continues counting from where it left off, excluding the paused span`() {
        val clock = PausableElapsedClock()
        clock.pause(nowMs = 10_000L)
        clock.resume(nowMs = 15_000L) // paused for 5s

        val elapsed = clock.elapsedMs(nowMs = 18_000L, rawElapsedMs = 18_000L) // 3s of real time since resume

        assertEquals(13_000L, elapsed)
    }

    @Test
    fun `multiple pause-resume spans all get excluded`() {
        val clock = PausableElapsedClock()
        clock.pause(nowMs = 1_000L)
        clock.resume(nowMs = 3_000L) // +2s paused
        clock.pause(nowMs = 5_000L)
        clock.resume(nowMs = 6_000L) // +1s paused

        val elapsed = clock.elapsedMs(nowMs = 10_000L, rawElapsedMs = 10_000L)

        assertEquals(7_000L, elapsed) // 10s raw - 3s total paused
    }

    @Test
    fun `pause is a no-op if already paused`() {
        val clock = PausableElapsedClock()
        clock.pause(nowMs = 1_000L)
        clock.pause(nowMs = 2_000L) // must not reset the pause start

        val elapsed = clock.elapsedMs(nowMs = 5_000L, rawElapsedMs = 5_000L)

        assertEquals(1_000L, elapsed) // paused since 1_000, not 2_000
    }

    @Test
    fun `resume is a no-op if not currently paused`() {
        val clock = PausableElapsedClock()

        clock.resume(nowMs = 5_000L)

        assertEquals(5_000L, clock.elapsedMs(nowMs = 5_000L, rawElapsedMs = 5_000L))
    }

    @Test
    fun `isPaused reflects current state`() {
        val clock = PausableElapsedClock()
        assertFalse(clock.isPaused())

        clock.pause(nowMs = 1_000L)
        assertTrue(clock.isPaused())

        clock.resume(nowMs = 2_000L)
        assertFalse(clock.isPaused())
    }

    @Test
    fun `reset clears accumulated pause time`() {
        val clock = PausableElapsedClock()
        clock.pause(nowMs = 1_000L)
        clock.resume(nowMs = 3_000L)

        clock.reset()

        assertEquals(10_000L, clock.elapsedMs(nowMs = 10_000L, rawElapsedMs = 10_000L))
        assertFalse(clock.isPaused())
    }
}
