package com.montauk.voicecapture.audio

import kotlin.math.sqrt

/**
 * Aggregates PCM16 mono samples, fed in arbitrary-sized chunks (as they
 * arrive from [AudioEngine.onPcmFrame]), into fixed ~[windowMs] windows and
 * emits one normalized (0f..1f) RMS level per window via [onWindow].
 *
 * Pure Kotlin (no Android dependency) so the windowing and RMS math is
 * JVM-testable. Deliberately reuses the same PCM tee [AudioEngine] already
 * feeds to the STT client rather than opening a second [android.media.AudioRecord] --
 * the recording screen's mic-level bar and the "(silence)" hint (see
 * [com.montauk.voicecapture.service.SilenceDetector]) are both driven off
 * this one meter.
 */
class MicLevelMeter(
    sampleRateHz: Int,
    channelCount: Int = 1,
    windowMs: Int = DEFAULT_WINDOW_MS,
    private val onWindow: (Float) -> Unit,
) {
    private val samplesPerWindow: Int = (sampleRateHz.toLong() * channelCount * windowMs / 1000).toInt().coerceAtLeast(1)

    private var sumSquares: Long = 0L
    private var sampleCount: Int = 0

    /** Feed one chunk of interleaved little-endian PCM16 bytes; [length] must be even. */
    fun onPcmFrame(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset + 1 < length) {
            val lo = pcm[offset].toInt() and 0xFF
            val hi = pcm[offset + 1].toInt() // sign-extends; combined below reconstructs signed 16-bit sample
            val sample = (hi shl 8) or lo
            sumSquares += sample.toLong() * sample.toLong()
            sampleCount++
            offset += 2
            if (sampleCount >= samplesPerWindow) {
                emitWindow()
            }
        }
    }

    private fun emitWindow() {
        val meanSquare = sumSquares.toDouble() / sampleCount
        val rms = sqrt(meanSquare)
        val normalized = (rms / MAX_AMPLITUDE).toFloat().coerceIn(0f, 1f)
        sumSquares = 0L
        sampleCount = 0
        onWindow(normalized)
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 100
        private const val MAX_AMPLITUDE = 32768.0
    }
}
