package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuckAnimationEngineTest {

    private fun engine(
        initialState: DuckState = DuckState.ATTENTIVE,
        pulseDurationMs: Long = 100L,
        celebratePulseDurationMs: Long = 60L,
    ) = DuckAnimationEngine(
        initialState = initialState,
        pulseDurationMs = pulseDurationMs,
        celebratePulseDurationMs = celebratePulseDurationMs,
    )

    // --- base-state transitions ---

    @Test
    fun `renders the initial base state immediately`() {
        val e = engine(initialState = DuckState.ATTENTIVE)
        assertEquals(DuckVisual.Pose(DuckFrame.ATTENTIVE), e.tick(0L))
    }

    @Test
    fun `setState switches the rendered frame immediately when no pulse is playing`() {
        val e = engine(initialState = DuckState.ATTENTIVE)
        e.tick(0L)
        e.setState(DuckState.SLEEP)
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEP), e.tick(0L))
        e.setState(DuckState.THINK)
        assertEquals(DuckVisual.Pose(DuckFrame.THINK), e.tick(0L))
    }

    @Test
    fun `state reports the current base state via the public property`() {
        val e = engine(initialState = DuckState.ATTENTIVE)
        assertEquals(DuckState.ATTENTIVE, e.state)
        e.setState(DuckState.SLEEP)
        assertEquals(DuckState.SLEEP, e.state)
    }

    // --- pulse one-shots + return-to-base ---

    @Test
    fun `triggerPulse plays the pulse frame then reverts to the current base state`() {
        val e = engine(initialState = DuckState.ATTENTIVE, pulseDurationMs = 100L)
        e.triggerPulse(DuckPulse.BLINK, nowMs = 1_000L)

        assertEquals(DuckVisual.Pose(DuckFrame.BLINK), e.tick(1_000L))
        assertEquals(DuckVisual.Pose(DuckFrame.BLINK), e.tick(1_099L))
        // Pulse duration elapsed -- reverts to the base state.
        assertEquals(DuckVisual.Pose(DuckFrame.ATTENTIVE), e.tick(1_100L))
    }

    @Test
    fun `each pulse kind resolves to its own frame`() {
        val e = engine()
        e.triggerPulse(DuckPulse.NOD, nowMs = 0L)
        assertEquals(DuckVisual.Pose(DuckFrame.NOD), e.tick(0L))

        val e2 = engine()
        e2.triggerPulse(DuckPulse.RAISE_HAND, nowMs = 0L)
        assertEquals(DuckVisual.Pose(DuckFrame.RAISE_HAND), e2.tick(0L))

        val e3 = engine()
        e3.triggerPulse(DuckPulse.WRITE, nowMs = 0L)
        assertEquals(DuckVisual.Pose(DuckFrame.WRITE), e3.tick(0L))
    }

    @Test
    fun `a pulse reverts to whatever base state is current, not the one active when it started`() {
        val e = engine(initialState = DuckState.ATTENTIVE, pulseDurationMs = 100L)
        e.triggerPulse(DuckPulse.BLINK, nowMs = 0L)
        assertEquals(DuckVisual.Pose(DuckFrame.BLINK), e.tick(0L))

        // The base state changes underneath the pulse -- setState takes
        // effect on `state` immediately, but the pulse keeps rendering
        // until it expires.
        e.setState(DuckState.SLEEP)
        assertEquals(DuckVisual.Pose(DuckFrame.BLINK), e.tick(50L))

        // Once the pulse expires, it reverts to SLEEP (the new state), not
        // ATTENTIVE (what was active when the pulse started).
        assertEquals(DuckVisual.Pose(DuckFrame.SLEEP), e.tick(100L))
    }

    // --- queuing: don't interrupt mid-pulse, drop stale ones ---

    @Test
    fun `triggering a pulse while one is playing does not interrupt it`() {
        val e = engine(pulseDurationMs = 100L)
        e.triggerPulse(DuckPulse.BLINK, nowMs = 0L)
        e.triggerPulse(DuckPulse.NOD, nowMs = 10L)

        // Still BLINK -- the NOD request is queued, not applied immediately.
        assertEquals(DuckVisual.Pose(DuckFrame.BLINK), e.tick(50L))
        // BLINK expires at 100; the queued NOD then plays for its own 100ms window.
        assertEquals(DuckVisual.Pose(DuckFrame.NOD), e.tick(100L))
        assertEquals(DuckVisual.Pose(DuckFrame.NOD), e.tick(199L))
        assertEquals(DuckVisual.Pose(DuckFrame.ATTENTIVE), e.tick(200L))
    }

    @Test
    fun `only the most recently queued pulse survives -- superseded requests are dropped`() {
        val e = engine(pulseDurationMs = 100L)
        e.triggerPulse(DuckPulse.BLINK, nowMs = 0L)
        e.triggerPulse(DuckPulse.NOD, nowMs = 10L)
        e.triggerPulse(DuckPulse.WRITE, nowMs = 20L) // supersedes the queued NOD

        assertEquals(DuckVisual.Pose(DuckFrame.BLINK), e.tick(50L))
        // WRITE plays next, not NOD.
        assertEquals(DuckVisual.Pose(DuckFrame.WRITE), e.tick(100L))
    }

    // --- celebrate: its own shorter pulse duration ---

    @Test
    fun `CELEBRATE plays for celebratePulseDurationMs, not the generic pulseDurationMs`() {
        val e = engine(pulseDurationMs = 700L, celebratePulseDurationMs = 600L)
        e.triggerPulse(DuckPulse.CELEBRATE, nowMs = 0L)

        assertEquals(DuckVisual.Pose(DuckFrame.CELEBRATE), e.tick(0L))
        assertEquals(DuckVisual.Pose(DuckFrame.CELEBRATE), e.tick(599L))
        // Reverts at 600ms -- well before the generic 700ms pulse duration would have.
        assertEquals(DuckVisual.Pose(DuckFrame.ATTENTIVE), e.tick(600L))
    }

    @Test
    fun `CELEBRATE queued behind another pulse still uses its own duration once it starts`() {
        val e = engine(pulseDurationMs = 100L, celebratePulseDurationMs = 60L)
        e.triggerPulse(DuckPulse.BLINK, nowMs = 0L)
        e.triggerPulse(DuckPulse.CELEBRATE, nowMs = 10L) // queued

        e.tick(99L) // still BLINK
        assertEquals(DuckVisual.Pose(DuckFrame.CELEBRATE), e.tick(100L)) // CELEBRATE starts
        assertEquals(DuckVisual.Pose(DuckFrame.CELEBRATE), e.tick(159L))
        assertEquals(DuckVisual.Pose(DuckFrame.ATTENTIVE), e.tick(160L)) // 100 + 60
    }

    // --- msUntilNextTransition / activePulseElapsedMs (scheduling hooks) ---

    @Test
    fun `msUntilNextTransition is null when no pulse is playing`() {
        val e = engine()
        e.tick(0L)
        assertNull(e.msUntilNextTransition(0L))
    }

    @Test
    fun `msUntilNextTransition counts down to the active pulse's expiry`() {
        val e = engine(pulseDurationMs = 100L)
        e.triggerPulse(DuckPulse.BLINK, nowMs = 0L)
        e.tick(0L)
        assertEquals(100L, e.msUntilNextTransition(0L))
        assertEquals(40L, e.msUntilNextTransition(60L))
        assertEquals(0L, e.msUntilNextTransition(150L))
    }

    @Test
    fun `activePulseElapsedMs is null when no pulse is playing`() {
        val e = engine()
        e.tick(0L)
        assertNull(e.activePulseElapsedMs(0L))
    }

    @Test
    fun `activePulseElapsedMs tracks time since the active pulse started`() {
        val e = engine(celebratePulseDurationMs = 600L)
        e.triggerPulse(DuckPulse.CELEBRATE, nowMs = 1_000L)
        e.tick(1_000L)
        assertEquals(0L, e.activePulseElapsedMs(1_000L))
        assertEquals(250L, e.activePulseElapsedMs(1_250L))
        assertEquals(599L, e.activePulseElapsedMs(1_599L))
    }
}
