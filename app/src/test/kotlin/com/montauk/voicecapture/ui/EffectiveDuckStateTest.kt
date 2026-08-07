package com.montauk.voicecapture.ui

import com.montauk.voicecapture.duck.DuckState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bead asn-dp2.5: pure-function coverage of [effectiveDuckState]'s priority
 * (highest first: [DuckState.SLEEP] > [DuckState.WRITE] > [DuckState.THINK]
 * > the ATTENTIVE/DROWSY base) -- no Compose/Robolectric needed.
 * [RecordingScreenLayoutATest] separately covers the live render of the
 * held WRITE pose while the notes card is actually up.
 */
class EffectiveDuckStateTest {

    @Test
    fun `neither override active -- the base state passes through unchanged`() {
        assertEquals(DuckState.ATTENTIVE, effectiveDuckState(DuckState.ATTENTIVE, thinkActive = false, notesCardActive = false))
        assertEquals(DuckState.DROWSY, effectiveDuckState(DuckState.DROWSY, thinkActive = false, notesCardActive = false))
    }

    @Test
    fun `thinkActive alone overrides the base state with THINK`() {
        assertEquals(DuckState.THINK, effectiveDuckState(DuckState.ATTENTIVE, thinkActive = true, notesCardActive = false))
        assertEquals(DuckState.THINK, effectiveDuckState(DuckState.DROWSY, thinkActive = true, notesCardActive = false))
    }

    @Test
    fun `notesCardActive overrides ATTENTIVE with the held WRITE pose`() {
        assertEquals(DuckState.WRITE, effectiveDuckState(DuckState.ATTENTIVE, thinkActive = false, notesCardActive = true))
    }

    @Test
    fun `notesCardActive overrides DROWSY with the held WRITE pose`() {
        assertEquals(DuckState.WRITE, effectiveDuckState(DuckState.DROWSY, thinkActive = false, notesCardActive = true))
    }

    @Test
    fun `notesCardActive overrides THINK too -- a card up implies the round already completed`() {
        assertEquals(DuckState.WRITE, effectiveDuckState(DuckState.ATTENTIVE, thinkActive = true, notesCardActive = true))
    }

    @Test
    fun `notesCardActive never overrides SLEEP -- paused suspends the card's own business too`() {
        assertEquals(DuckState.SLEEP, effectiveDuckState(DuckState.SLEEP, thinkActive = false, notesCardActive = true))
        assertEquals(DuckState.SLEEP, effectiveDuckState(DuckState.SLEEP, thinkActive = true, notesCardActive = true))
    }
}
