package com.montauk.voicecapture.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MicLevelMeterTest {

    /** Little-endian PCM16 bytes for a constant-amplitude signal, [count] samples long. */
    private fun constantAmplitudeBytes(amplitude: Int, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            bytes[i * 2] = (amplitude and 0xFF).toByte()
            bytes[i * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `silence produces near-zero level`() {
        var lastLevel = -1f
        val meter = MicLevelMeter(sampleRateHz = 16_000, onWindow = { lastLevel = it })
        // 100ms window at 16kHz mono = 1600 samples.
        meter.onPcmFrame(constantAmplitudeBytes(0, 1600), 1600 * 2)
        assertEquals(0f, lastLevel, 0.0001f)
    }

    @Test
    fun `full-scale constant tone normalizes to 1`() {
        var lastLevel = -1f
        val meter = MicLevelMeter(sampleRateHz = 16_000, onWindow = { lastLevel = it })
        meter.onPcmFrame(constantAmplitudeBytes(32767, 1600), 1600 * 2)
        assertTrue("expected level near 1.0, got $lastLevel", abs(lastLevel - 1f) < 0.01f)
    }

    @Test
    fun `emits one window per samplesPerWindow samples`() {
        var windowCount = 0
        val meter = MicLevelMeter(sampleRateHz = 16_000, onWindow = { windowCount++ })
        // 3 windows' worth of samples in one call.
        meter.onPcmFrame(constantAmplitudeBytes(100, 1600 * 3), 1600 * 3 * 2)
        assertEquals(3, windowCount)
    }

    @Test
    fun `partial window carries over across calls`() {
        var windowCount = 0
        val meter = MicLevelMeter(sampleRateHz = 16_000, onWindow = { windowCount++ })
        meter.onPcmFrame(constantAmplitudeBytes(100, 800), 800 * 2) // half a window
        assertEquals(0, windowCount)
        meter.onPcmFrame(constantAmplitudeBytes(100, 800), 800 * 2) // completes the window
        assertEquals(1, windowCount)
    }
}
