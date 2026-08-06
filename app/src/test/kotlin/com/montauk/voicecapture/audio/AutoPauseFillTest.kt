package com.montauk.voicecapture.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoPauseFillTest {

    @Test
    fun `zero before the leading invisible span ends`() {
        // 10s total, 5s fill -> leading invisible span is 5s.
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 0L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = true), 0f)
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 4_999L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = true), 0f)
    }

    @Test
    fun `ramps linearly across the trailing fill window`() {
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 5_000L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = true), 0f)
        assertEquals(0.5f, autoPauseFillFraction(continuousQuietMs = 7_500L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = true), 0f)
        assertEquals(1f, autoPauseFillFraction(continuousQuietMs = 10_000L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = true), 0f)
    }

    @Test
    fun `stays clamped at 1 past the total threshold (already auto-paused)`() {
        assertEquals(1f, autoPauseFillFraction(continuousQuietMs = 60_000L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = true), 0f)
    }

    @Test
    fun `zero the instant continuousQuietMs resets (any speech cancels the fill)`() {
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 0L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = true), 0f)
    }

    @Test
    fun `zero when auto-pause is disabled regardless of continuousQuietMs`() {
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 10_000L, totalThresholdMs = 10_000L, fillDurationMs = 5_000L, enabled = false), 0f)
    }

    @Test
    fun `larger total thresholds keep the fill window fixed at fillDurationMs, only the leading span grows`() {
        // 60s total, 5s fill -> leading invisible span is 55s.
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 55_000L, totalThresholdMs = 60_000L, fillDurationMs = 5_000L, enabled = true), 0f)
        assertEquals(0.5f, autoPauseFillFraction(continuousQuietMs = 57_500L, totalThresholdMs = 60_000L, fillDurationMs = 5_000L, enabled = true), 0f)
        assertEquals(1f, autoPauseFillFraction(continuousQuietMs = 60_000L, totalThresholdMs = 60_000L, fillDurationMs = 5_000L, enabled = true), 0f)
    }

    @Test
    fun `never divides by a non-positive fill duration`() {
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 10_000L, totalThresholdMs = 10_000L, fillDurationMs = 0L, enabled = true), 0f)
    }

    @Test
    fun `a threshold at or under fillDurationMs never leaves the leading span negative`() {
        // totalThresholdMs == fillDurationMs -> the whole span is fill, no invisible lead-in.
        assertEquals(0f, autoPauseFillFraction(continuousQuietMs = 0L, totalThresholdMs = 5_000L, fillDurationMs = 5_000L, enabled = true), 0f)
        assertEquals(1f, autoPauseFillFraction(continuousQuietMs = 5_000L, totalThresholdMs = 5_000L, fillDurationMs = 5_000L, enabled = true), 0f)
    }
}
