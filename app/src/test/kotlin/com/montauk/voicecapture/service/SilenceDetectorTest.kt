package com.montauk.voicecapture.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    private val lowRms = 0.005f
    private val loudRms = 0.5f

    @Test
    fun `not silent before timeout even if rms is low`() {
        val detector = SilenceDetector(silenceTimeoutMs = 4_000L, rmsThreshold = 0.02f)
        assertFalse(detector.isSilent(nowMs = 3_999L, recordingStartMs = 0L, currentRms = lowRms))
    }

    @Test
    fun `silent after timeout when rms is low and no activity ever happened`() {
        val detector = SilenceDetector(silenceTimeoutMs = 4_000L, rmsThreshold = 0.02f)
        assertTrue(detector.isSilent(nowMs = 4_000L, recordingStartMs = 0L, currentRms = lowRms))
    }

    @Test
    fun `not silent when rms is loud even after timeout`() {
        val detector = SilenceDetector(silenceTimeoutMs = 4_000L, rmsThreshold = 0.02f)
        assertFalse(detector.isSilent(nowMs = 10_000L, recordingStartMs = 0L, currentRms = loudRms))
    }

    @Test
    fun `transcript activity resets the timeout clock`() {
        val detector = SilenceDetector(silenceTimeoutMs = 4_000L, rmsThreshold = 0.02f)
        detector.onTranscriptActivity(atMs = 5_000L)
        assertFalse(detector.isSilent(nowMs = 8_000L, recordingStartMs = 0L, currentRms = lowRms))
        assertTrue(detector.isSilent(nowMs = 9_001L, recordingStartMs = 0L, currentRms = lowRms))
    }

    @Test
    fun `works with no stt configured -- baseline is recording start, not activity`() {
        // Simulates an offline session: onTranscriptActivity is never called because
        // there's no STT client, so silence must still trigger off recordingStartMs alone.
        val detector = SilenceDetector(silenceTimeoutMs = 4_000L, rmsThreshold = 0.02f)
        assertTrue(detector.isSilent(nowMs = 100_000L, recordingStartMs = 95_000L, currentRms = lowRms))
    }

    @Test
    fun `reset clears prior activity so a new session starts from its own baseline`() {
        val detector = SilenceDetector(silenceTimeoutMs = 4_000L, rmsThreshold = 0.02f)
        detector.onTranscriptActivity(atMs = 50_000L)
        detector.reset()
        assertTrue(detector.isSilent(nowMs = 4_000L, recordingStartMs = 0L, currentRms = lowRms))
    }
}
