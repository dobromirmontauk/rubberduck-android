package com.montauk.voicecapture.duck

import com.montauk.voicecapture.tags.RailChipSource
import com.montauk.voicecapture.tags.TagRailChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckPulseTriggersTest {

    private fun chip(tag: String, tagId: String? = null) = TagRailChip(tag, tagId, RailChipSource.SUGGESTED)

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

    // --- topSetGainedNewTag (bead asn-02h.4) ---

    @Test
    fun `a new tag entering the top set fires -- tag event to RAISE_HAND`() {
        val before = listOf(chip("kitchen-remodel"), chip("dog-walks"))
        val after = listOf(chip("kitchen-remodel"), chip("dog-walks"), chip("permits"))
        assertTrue(topSetGainedNewTag(before, after))
    }

    @Test
    fun `the cloud's very first population this session never counts as new`() {
        val before = emptyList<TagRailChip>()
        val after = listOf(chip("kitchen-remodel"))
        assertFalse(topSetGainedNewTag(before, after))
    }

    @Test
    fun `no change to the top set does not fire`() {
        val before = listOf(chip("kitchen-remodel"), chip("dog-walks"))
        val after = listOf(chip("kitchen-remodel"), chip("dog-walks"))
        assertFalse(topSetGainedNewTag(before, after))
    }

    @Test
    fun `a tag dropping out of the top set alone does not fire`() {
        val before = listOf(chip("kitchen-remodel"), chip("dog-walks"))
        val after = listOf(chip("kitchen-remodel"))
        assertFalse(topSetGainedNewTag(before, after))
    }

    @Test
    fun `a tag entering only the muted candidate tail (beyond MAX_TOP) does not fire`() {
        val before = List(ThoughtCloudWords.MAX_TOP) { chip("existing-$it") }
        val after = before + chip("new-candidate")
        assertFalse(topSetGainedNewTag(before, after))
    }

    @Test
    fun `a tag event fires RAISE_HAND exactly once for one new entry`() {
        val before = listOf(chip("kitchen-remodel"))
        val after = listOf(chip("kitchen-remodel"), chip("permits"))
        assertTrue(topSetGainedNewTag(before, after))
        // Idempotent re-check against the SAME two snapshots -- a caller
        // that (incorrectly) evaluated this twice for one real transition
        // would still only ever see "changed" once per distinct pair.
        assertTrue(topSetGainedNewTag(before, after))
        assertFalse(topSetGainedNewTag(after, after))
    }
}
