package com.montauk.voicecapture.audio

import kotlin.math.log10
import kotlin.math.round

/**
 * Pure Kotlin level -> visual mapping for the recording screen's loudness
 * meter. Feeds off the same normalized (0f..1f) RMS windows [MicLevelMeter]
 * emits (~every 100ms) and turns them into a fixed-length scrolling history
 * of bar heights (0f..1f) for a Canvas-drawn VU-style row. No Android
 * dependency, so the curve/smoothing/quantization math is JVM-testable
 * independent of Compose.
 *
 * Three things happen per window, in order:
 *  1. [normalize] recasts the raw linear RMS onto a dBFS-style scale from
 *     [floorDb] (visibly empty -- chosen to line up with [SilenceDetector]'s
 *     ~0.02 RMS "(silence)" threshold, roughly -34dBFS) up to 0dB (full
 *     scale). Linear RMS under-represents normal speech, which rarely
 *     approaches full scale, so a log curve is what makes a quiet-but-present
 *     voice register as visible motion instead of a sliver at the bottom of
 *     a linear bar.
 *  2. Asymmetric attack/decay smoothing: attacks fast (jumps most of the way
 *     to a louder target in a single ~100ms window, so a spoken syllable
 *     reads as instant motion) and decays slowly (falls gracefully over
 *     roughly a second) so speech reads as continuous movement rather than a
 *     flickering needle, and a pause after a loud word doesn't snap to zero.
 *  3. Quantization to [levelSteps] discrete heights, which gives the bars
 *     their chunky analog-VU-meter look and throws away sub-pixel height
 *     changes that would just be visual noise.
 */
class LoudnessVisualizer(
    private val historyLength: Int = DEFAULT_HISTORY_LENGTH,
    private val floorDb: Float = DEFAULT_FLOOR_DB,
    private val attackPerWindow: Float = DEFAULT_ATTACK_PER_WINDOW,
    private val decayPerWindow: Float = DEFAULT_DECAY_PER_WINDOW,
    private val levelSteps: Int = DEFAULT_LEVEL_STEPS,
) {
    private var smoothed: Float = 0f
    private val buffer = ArrayDeque<Float>(historyLength).apply {
        repeat(historyLength) { addLast(0f) }
    }

    /** Always exactly [historyLength] long; oldest first, newest (most recent window) last. */
    val history: List<Float> get() = buffer.toList()

    /**
     * Feed one normalized (0f..1f) RMS window from [MicLevelMeter]. Returns
     * the quantized bar height just appended to [history]'s tail.
     */
    fun onLevel(rawRms: Float): Float {
        val target = normalize(rawRms)
        smoothed = if (target > smoothed) {
            smoothed + attackPerWindow * (target - smoothed)
        } else {
            smoothed + decayPerWindow * (target - smoothed)
        }
        val quantized = quantize(smoothed)
        buffer.removeFirst()
        buffer.addLast(quantized)
        return quantized
    }

    /** Linear RMS (0f..1f) -> perceptual 0f..1f, 0 at/under [floorDb], 1 at full scale. */
    private fun normalize(rawRms: Float): Float {
        val clamped = rawRms.coerceIn(0f, 1f)
        if (clamped <= 0f) return 0f
        val db = 20f * log10(clamped)
        return ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
    }

    private fun quantize(value: Float): Float {
        if (levelSteps <= 1) return value.coerceIn(0f, 1f)
        return (round(value * levelSteps) / levelSteps).coerceIn(0f, 1f)
    }

    companion object {
        const val DEFAULT_HISTORY_LENGTH = 28
        /** dBFS floor below which a level renders visibly empty; matches SilenceDetector's 0.02 RMS threshold (~-34dBFS). */
        const val DEFAULT_FLOOR_DB = -34f
        const val DEFAULT_ATTACK_PER_WINDOW = 0.9f
        const val DEFAULT_DECAY_PER_WINDOW = 0.12f
        const val DEFAULT_LEVEL_STEPS = 12
    }
}
