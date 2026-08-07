package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckPulseTriggersTest {

    // --- BlinkHeartbeat (bead asn-02h.1) ---

    @Test
    fun `partial arrival fires BLINK`() {
        val heartbeat = BlinkHeartbeat(throttleMs = 2_500L)
        assertTrue(heartbeat.onInboundPartial(nowMs = 0L))
    }

    @Test
    fun `a burst of partials within the throttle window fires exactly one BLINK`() {
        val heartbeat = BlinkHeartbeat(throttleMs = 2_500L)
        assertTrue(heartbeat.onInboundPartial(nowMs = 0L))
        assertFalse(heartbeat.onInboundPartial(nowMs = 200L))
        assertFalse(heartbeat.onInboundPartial(nowMs = 900L))
        assertFalse(heartbeat.onInboundPartial(nowMs = 2_400L))
        // Exactly at the throttle boundary the window has fully elapsed again.
        assertTrue(heartbeat.onInboundPartial(nowMs = 2_500L))
    }

    @Test
    fun `chunk-send alone fires none -- there is no entry point for it`() {
        // BlinkHeartbeat's only entry point is onInboundPartial, driven
        // exclusively by a RESPONSE received from the STT engine (see its
        // class KDoc) -- an outbound audio-chunk send has no call into this
        // class at all, so a session that only ever sends audio (engine
        // never responds) never blinks. Asserted here by construction: a
        // fresh heartbeat that's never been told about an inbound partial
        // has never fired.
        val heartbeat = BlinkHeartbeat(throttleMs = 2_500L)
        assertFalse(heartbeat.hasEverBlinked())
    }

    @Test
    fun `reset clears the throttle window so a fresh session blinks immediately`() {
        val heartbeat = BlinkHeartbeat(throttleMs = 2_500L)
        assertTrue(heartbeat.onInboundPartial(nowMs = 0L))
        assertFalse(heartbeat.onInboundPartial(nowMs = 100L))
        heartbeat.reset()
        assertTrue(heartbeat.onInboundPartial(nowMs = 150L))
    }

    // --- nodPulseForFinalSegment (bead asn-02h.2) ---

    @Test
    fun `final-segment fires NOD`() {
        assertEquals(DuckPulse.NOD, nodPulseForFinalSegment())
    }

    // --- shouldPlayPulse (bead asn-02h.3) ---

    @Test
    fun `WRITE pulse is a no-op while the base state is already WRITE (card visible)`() {
        val event = DuckPulseEvent(DuckPulse.WRITE, nonce = 1)
        assertFalse(shouldPlayPulse(event, currentBaseState = DuckState.WRITE))
    }

    @Test
    fun `WRITE pulse fires when the base state is not WRITE (card hidden)`() {
        val event = DuckPulseEvent(DuckPulse.WRITE, nonce = 1)
        assertTrue(shouldPlayPulse(event, currentBaseState = DuckState.ATTENTIVE))
    }

    @Test
    fun `other pulses always play regardless of the current base state`() {
        val event = DuckPulseEvent(DuckPulse.NOD, nonce = 1)
        assertTrue(shouldPlayPulse(event, currentBaseState = DuckState.WRITE))
    }
}
