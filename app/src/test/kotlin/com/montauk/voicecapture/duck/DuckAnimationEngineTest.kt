package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckAnimationEngineTest {

    private fun engine(
        initialState: DuckState = DuckState.LISTENING,
        frameDurationMs: Long = 100L,
        sleepyFrameDurationMs: Long = 500L,
        sleepingFrameDurationMs: Long = 700L,
        blinkEveryMs: Long = 1_000L,
        crossfadeMs: Long = 200L,
    ) = DuckAnimationEngine(
        initialState = initialState,
        frameDurationMs = frameDurationMs,
        sleepyFrameDurationMs = sleepyFrameDurationMs,
        sleepingFrameDurationMs = sleepingFrameDurationMs,
        blinkEveryMs = blinkEveryMs,
        crossfadeMs = crossfadeMs,
    )

    // --- state -> sequence mapping ---

    @Test
    fun `LISTENING plays the listening-intro sequence first`() {
        val e = engine(initialState = DuckState.LISTENING)
        assertEquals(DuckVisual.Pose(DuckFrame.LISTENING_INTRO_1), e.tick(0L))
        assertEquals(DuckVisual.Pose(DuckFrame.LISTENING_INTRO_2), e.tick(100L))
        assertEquals(DuckVisual.Pose(DuckFrame.LISTENING_INTRO_3), e.tick(200L))
    }

    @Test
    fun `LISTENING settles into the idle-breathing loop after the intro finishes`() {
        val e = engine(initialState = DuckState.LISTENING)
        e.tick(0L) // establishes phase at t=0
        // 3 intro frames * 100ms = 300ms; the 4th frame is past the intro.
        val visual = e.tick(300L)
        assertEquals(DuckVisual.Pose(DuckFrame.IDLE_BREATHING_1), visual)
    }

    @Test
    fun `idle-breathing loops`() {
        val e = engine(initialState = DuckState.LISTENING)
        e.tick(0L)
        e.tick(300L) // enters idle at t=300 -> IDLE_BREATHING_1
        assertEquals(DuckVisual.Pose(DuckFrame.IDLE_BREATHING_2), e.tick(400L))
        assertEquals(DuckVisual.Pose(DuckFrame.IDLE_BREATHING_3), e.tick(500L))
        assertEquals(DuckVisual.Pose(DuckFrame.IDLE_BREATHING_4), e.tick(600L))
        // Loops back to frame 1 (4 frames * 100ms = 400ms cycle).
        assertEquals(DuckVisual.Pose(DuckFrame.IDLE_BREATHING_1), e.tick(700L))
    }

    @Test
    fun `idle-breathing is interrupted by a blink every blinkEveryMs then resumes idle`() {
        val e = engine(initialState = DuckState.LISTENING, blinkEveryMs = 1_000L)
        e.tick(0L)
        e.tick(300L) // enters idle at t=300

        // Blink fires 1000ms after idle was entered, i.e. at t=1300.
        val blinkStart = e.tick(1_300L)
        assertEquals(DuckVisual.Pose(DuckFrame.BLINK_1), blinkStart)
        assertEquals(DuckVisual.Pose(DuckFrame.BLINK_2), e.tick(1_400L))
        assertEquals(DuckVisual.Pose(DuckFrame.BLINK_3), e.tick(1_500L))
        // Blink sequence (3 frames) finishes at 1_300 + 300 = 1_600; back to idle frame 1.
        assertEquals(DuckVisual.Pose(DuckFrame.IDLE_BREATHING_1), e.tick(1_600L))
    }

    @Test
    fun `SLEEPY loops its 2 frames at the slower cadence`() {
        val e = engine(initialState = DuckState.SLEEPY, sleepyFrameDurationMs = 500L)
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPY_1), e.tick(0L))
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPY_2), e.tick(500L))
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPY_1), e.tick(1_000L))
    }

    @Test
    fun `SLEEPING loops its 2 frames at the deeper (slower) cadence`() {
        val e = engine(initialState = DuckState.SLEEPING, sleepingFrameDurationMs = 700L)
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPING_1), e.tick(0L))
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPING_2), e.tick(700L))
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPING_1), e.tick(1_400L))
    }

    @Test
    fun `THINKING loops its 3 frames`() {
        val e = engine(initialState = DuckState.THINKING)
        assertEquals(DuckVisual.Pose(DuckFrame.THINKING_1), e.tick(0L))
        assertEquals(DuckVisual.Pose(DuckFrame.THINKING_2), e.tick(100L))
        assertEquals(DuckVisual.Pose(DuckFrame.THINKING_3), e.tick(200L))
        assertEquals(DuckVisual.Pose(DuckFrame.THINKING_1), e.tick(300L))
    }

    // --- happy-bounce (a one-shot overlay, not a DuckState) ---

    @Test
    fun `triggerHappyBounce plays its 3 frames once, then resumes the underlying state`() {
        val e = engine(initialState = DuckState.SLEEPY, sleepyFrameDurationMs = 500L)
        e.tick(0L) // establish SLEEPY
        e.triggerHappyBounce(1_000L)

        assertEquals(DuckVisual.Pose(DuckFrame.HAPPY_BOUNCE_1), e.tick(1_000L))
        assertEquals(DuckVisual.Pose(DuckFrame.HAPPY_BOUNCE_2), e.tick(1_100L))
        assertEquals(DuckVisual.Pose(DuckFrame.HAPPY_BOUNCE_3), e.tick(1_200L))
        // 3 frames * 100ms = 300ms; past that, back to SLEEPY.
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPY_1), e.tick(1_300L))
    }

    @Test
    fun `triggerHappyBounce does not change state or interrupt a pending state transition`() {
        val e = engine(initialState = DuckState.LISTENING)
        e.tick(0L)
        e.triggerHappyBounce(1_000L)
        e.tick(1_000L)
        assertEquals(DuckState.LISTENING, e.state)
    }

    @Test
    fun `triggerHappyBounce interrupts SLEEPING too, then resumes it`() {
        val e = engine(initialState = DuckState.SLEEPING, sleepingFrameDurationMs = 700L)
        e.tick(0L)
        e.triggerHappyBounce(1_000L)

        assertEquals(DuckVisual.Pose(DuckFrame.HAPPY_BOUNCE_1), e.tick(1_000L))
        // 3 frames * 100ms = 300ms; past that, back to SLEEPING -- the
        // underlying SleepingPhase's own clock (started at t=0) never
        // paused, so at t=1300 it's mid-cycle (1300/700 = 1 -> frame 2), not
        // restarted at frame 1 -- see the class KDoc's "not literally
        // paused-and-resumed" note.
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPING_2), e.tick(1_300L))
    }

    @Test
    fun `setState switches sequence and resets frame timing`() {
        val e = engine(initialState = DuckState.LISTENING)
        e.tick(0L)
        e.tick(300L) // now mid idle-breathing

        e.setState(DuckState.SLEEPY, 10_000L)
        assertEquals(DuckState.SLEEPY, e.state)
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPY_1), e.tick(10_000L))
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPY_2), e.tick(10_500L))
    }

    @Test
    fun `setState back to LISTENING replays the intro, not the mid-idle frame it left off at`() {
        val e = engine(initialState = DuckState.LISTENING)
        e.tick(0L)
        e.tick(300L) // idle
        e.setState(DuckState.SLEEPY, 1_000L)
        e.setState(DuckState.LISTENING, 5_000L)
        assertEquals(DuckVisual.Pose(DuckFrame.LISTENING_INTRO_1), e.tick(5_000L))
    }

    @Test
    fun `setState to the current state is a no-op`() {
        val e = engine(initialState = DuckState.SLEEPY)
        e.tick(0L)
        e.setState(DuckState.SLEEPY, 999L) // should not reset phase timing
        // Still mid-cycle relative to the original t=0 start, not restarted at 999.
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEPY_2), e.tick(500L))
    }

    // --- crossfade triggering ---

    @Test
    fun `isCrossfading is true immediately after a state transition and false after the window elapses`() {
        val e = engine(initialState = DuckState.LISTENING, crossfadeMs = 200L)
        e.tick(0L)
        e.setState(DuckState.SLEEPING, 1_000L)

        assertTrue(e.isCrossfading(1_000L))
        assertTrue(e.isCrossfading(1_199L))
        assertFalse(e.isCrossfading(1_200L))
        assertFalse(e.isCrossfading(5_000L))
    }

    @Test
    fun `a no-op setState (same state) does not reset the crossfade window`() {
        val e = engine(initialState = DuckState.LISTENING, crossfadeMs = 200L)
        e.tick(0L)
        e.setState(DuckState.SLEEPY, 1_000L)
        assertTrue(e.isCrossfading(1_000L))

        // Crossfade window from the SLEEPY transition should elapse normally...
        assertFalse(e.isCrossfading(1_200L))
        // ...and calling setState with the SAME state again must not restart it.
        e.setState(DuckState.SLEEPY, 5_000L)
        assertFalse(e.isCrossfading(5_000L))
    }
}
