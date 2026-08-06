package com.montauk.voicecapture.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {

    @Test
    fun `a window above threshold is speaking, below is quiet`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)

        vad.onWindow(rms = 0.5f, windowMs = 100L)
        assertTrue(vad.isSpeaking)

        vad.onWindow(rms = 0.01f, windowMs = 100L)
        assertFalse(vad.isSpeaking)
    }

    @Test
    fun `continuousQuietMs accumulates across consecutive quiet windows`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)

        repeat(5) { vad.onWindow(rms = 0f, windowMs = 100L) }

        assertEquals(500L, vad.continuousQuietMs)
    }

    @Test
    fun `any speech window resets continuousQuietMs to zero`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)
        repeat(10) { vad.onWindow(rms = 0f, windowMs = 100L) }
        assertEquals(1_000L, vad.continuousQuietMs)

        vad.onWindow(rms = 0.5f, windowMs = 100L)

        assertEquals(0L, vad.continuousQuietMs)
        assertTrue(vad.isSpeaking)
    }

    @Test
    fun `a value exactly at threshold counts as speaking`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)

        vad.onWindow(rms = 0.02f, windowMs = 100L)

        assertTrue(vad.isSpeaking)
        assertEquals(0L, vad.continuousQuietMs)
    }

    @Test
    fun `reset clears isSpeaking, continuousQuietMs, and consecutiveSpeechWindows`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)
        vad.onWindow(rms = 0.5f, windowMs = 100L)
        repeat(3) { vad.onWindow(rms = 0f, windowMs = 100L) }

        vad.reset()

        assertFalse(vad.isSpeaking)
        assertEquals(0L, vad.continuousQuietMs)
        assertEquals(0, vad.consecutiveSpeechWindows)
    }

    // --- Bead asn-o63: resume-hysteresis counter ---

    @Test
    fun `consecutiveSpeechWindows counts up across back-to-back speaking windows`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)

        vad.onWindow(rms = 0.5f, windowMs = 100L)
        assertEquals(1, vad.consecutiveSpeechWindows)
        vad.onWindow(rms = 0.5f, windowMs = 100L)
        assertEquals(2, vad.consecutiveSpeechWindows)
        vad.onWindow(rms = 0.5f, windowMs = 100L)
        assertEquals(3, vad.consecutiveSpeechWindows)
    }

    @Test
    fun `a single quiet window resets consecutiveSpeechWindows to zero -- fixes the single-blip auto-resume bug`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)
        vad.onWindow(rms = 0.5f, windowMs = 100L) // one blip of "speech"
        assertEquals(1, vad.consecutiveSpeechWindows)

        vad.onWindow(rms = 0f, windowMs = 100L) // back to quiet immediately

        assertEquals(0, vad.consecutiveSpeechWindows)
    }

    @Test
    fun `consecutiveSpeechWindows stays at zero throughout a purely quiet run`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f)

        repeat(10) { vad.onWindow(rms = 0f, windowMs = 100L) }

        assertEquals(0, vad.consecutiveSpeechWindows)
    }
}
