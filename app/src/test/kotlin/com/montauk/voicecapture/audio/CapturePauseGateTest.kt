package com.montauk.voicecapture.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers bead asn-r60's core capture-pipeline contract: pause drops audio,
 * hard vs. soft have different buffer semantics, and a soft-pause resume
 * flushes (prepends) the ring buffer before live capture continues. See
 * [CapturePauseGate]'s KDoc for why this pure class -- not [AudioEngine]
 * itself -- is where that contract is verified.
 */
class CapturePauseGateTest {

    private fun chunk(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    @Test
    fun `ACTIVE mode persists every frame as-is`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)

        val decision = gate.onFrame(chunk(1, 2, 3), 3)

        assertEquals(1, decision.toPersist.size)
        assertArrayEquals(chunk(1, 2, 3), decision.toPersist[0])
    }

    @Test
    fun `HARD mode drops every frame -- nothing persisted, nothing buffered`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)
        gate.setMode(AudioPauseMode.HARD)

        val decision = gate.onFrame(chunk(1, 2, 3), 3)

        assertTrue("HARD mode must never persist a frame", decision.toPersist.isEmpty())
    }

    @Test
    fun `HARD mode retains no buffer -- resuming to ACTIVE persists nothing extra`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)
        gate.setMode(AudioPauseMode.HARD)
        gate.onFrame(chunk(1, 2, 3), 3)
        gate.onFrame(chunk(4, 5, 6), 3)

        val resumeDecision = gate.setMode(AudioPauseMode.ACTIVE)

        assertTrue("hard pause must retain no buffer at all", resumeDecision.toPersist.isEmpty())
    }

    @Test
    fun `SOFT mode buffers frames instead of persisting them`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)
        gate.setMode(AudioPauseMode.SOFT)

        val decision = gate.onFrame(chunk(1, 2, 3), 3)

        assertTrue("SOFT mode must never persist a frame directly", decision.toPersist.isEmpty())
    }

    @Test
    fun `resuming from SOFT to ACTIVE flushes the buffered frames oldest-first, prepended before live capture`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)
        gate.setMode(AudioPauseMode.SOFT)
        gate.onFrame(chunk(1, 1), 2)
        gate.onFrame(chunk(2, 2), 2)

        val resumeDecision = gate.setMode(AudioPauseMode.ACTIVE)
        val liveFrameDecision = gate.onFrame(chunk(3, 3), 2)

        assertEquals(2, resumeDecision.toPersist.size)
        assertArrayEquals(chunk(1, 1), resumeDecision.toPersist[0])
        assertArrayEquals(chunk(2, 2), resumeDecision.toPersist[1])
        // The live frame that arrives right after resume persists normally,
        // continuing chronologically after the flushed ring buffer.
        assertEquals(1, liveFrameDecision.toPersist.size)
        assertArrayEquals(chunk(3, 3), liveFrameDecision.toPersist[0])
    }

    @Test
    fun `escalating from SOFT directly to HARD discards the tentative buffer instead of persisting it`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)
        gate.setMode(AudioPauseMode.SOFT)
        gate.onFrame(chunk(1, 1), 2)
        gate.onFrame(chunk(2, 2), 2)

        val escalateDecision = gate.setMode(AudioPauseMode.HARD)

        assertTrue(
            "escalating to a hard/manual pause must discard the soft-pause buffer, not persist it",
            escalateDecision.toPersist.isEmpty(),
        )
        // And nothing lingers to flush later either.
        val laterResume = gate.setMode(AudioPauseMode.ACTIVE)
        assertTrue(laterResume.toPersist.isEmpty())
    }

    @Test
    fun `the ring buffer respects its capacity -- a long soft pause only retains the trailing window`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 4) // room for two 2-byte chunks
        gate.setMode(AudioPauseMode.SOFT)
        gate.onFrame(chunk(1, 1), 2)
        gate.onFrame(chunk(2, 2), 2)
        gate.onFrame(chunk(3, 3), 2) // should push out chunk(1,1)

        val resumeDecision = gate.setMode(AudioPauseMode.ACTIVE)

        assertEquals(2, resumeDecision.toPersist.size)
        assertArrayEquals(chunk(2, 2), resumeDecision.toPersist[0])
        assertArrayEquals(chunk(3, 3), resumeDecision.toPersist[1])
    }

    @Test
    fun `no-op transitions (ACTIVE to ACTIVE, HARD to HARD) never flush anything`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)

        assertTrue(gate.setMode(AudioPauseMode.ACTIVE).toPersist.isEmpty())

        gate.setMode(AudioPauseMode.HARD)
        assertTrue(gate.setMode(AudioPauseMode.HARD).toPersist.isEmpty())
    }

    @Test
    fun `reset clears mode back to ACTIVE and drops any buffered audio`() {
        val gate = CapturePauseGate(ringBufferCapacityBytes = 100)
        gate.setMode(AudioPauseMode.SOFT)
        gate.onFrame(chunk(1, 1), 2)

        gate.reset()

        assertEquals(AudioPauseMode.ACTIVE, gate.mode)
        val decision = gate.setMode(AudioPauseMode.ACTIVE) // already ACTIVE; must not surface stale buffered audio
        assertTrue(decision.toPersist.isEmpty())
    }
}
