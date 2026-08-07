package com.montauk.voicecapture.duck

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuckPulseStateHolderTest {

    @After
    fun tearDown() {
        DuckPulseStateHolder.reset()
    }

    @Test
    fun `emit publishes a DuckPulseEvent carrying the requested pulse`() {
        DuckPulseStateHolder.emit(DuckPulse.BLINK)
        assertEquals(DuckPulse.BLINK, DuckPulseStateHolder.events.value?.pulse)
    }

    @Test
    fun `emitting the same pulse twice in a row still produces two distinct events`() {
        DuckPulseStateHolder.emit(DuckPulse.NOD)
        val first = DuckPulseStateHolder.events.value
        DuckPulseStateHolder.emit(DuckPulse.NOD)
        val second = DuckPulseStateHolder.events.value
        assertNotEquals(first, second)
        assertNotEquals(first?.nonce, second?.nonce)
    }

    @Test
    fun `reset clears the last event`() {
        DuckPulseStateHolder.emit(DuckPulse.CELEBRATE)
        DuckPulseStateHolder.reset()
        assertNull(DuckPulseStateHolder.events.value)
    }
}
