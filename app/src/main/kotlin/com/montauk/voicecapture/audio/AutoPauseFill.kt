package com.montauk.voicecapture.audio

/**
 * Bead asn-o63: 0f..1f progress toward auto-pause, for the pause button's
 * gradient-fill warning. Pure function (no Android/Compose dependency,
 * matching this codebase's convention for [VoiceActivityDetector]/
 * [PcmRingBuffer]/[com.montauk.voicecapture.service.PausableElapsedClock] --
 * the actual math is what needs rigorous test coverage; the interim Compose
 * visual that consumes it does not) -- see
 * [com.montauk.voicecapture.service.TranscriptStateHolder]'s
 * `autoPauseFillFraction` field KDoc for the full semantics this implements.
 *
 * [continuousQuietMs] is [VoiceActivityDetector.continuousQuietMs]; [totalThresholdMs]
 * is [com.montauk.voicecapture.settings.AppSecretsStore.autoPauseSilenceThresholdMs];
 * [fillDurationMs] is the fixed [com.montauk.voicecapture.settings.AppSecretsStore.AUTO_PAUSE_FILL_DURATION_MS].
 * Returns 0f whenever [enabled] is false or [fillDurationMs] isn't positive
 * (defensive -- Settings' own minimum threshold option always keeps
 * `totalThresholdMs >= fillDurationMs`, so a negative leading span shouldn't
 * happen in practice, but this never divides by a non-positive duration
 * regardless).
 */
fun autoPauseFillFraction(
    continuousQuietMs: Long,
    totalThresholdMs: Long,
    fillDurationMs: Long,
    enabled: Boolean,
): Float {
    if (!enabled || fillDurationMs <= 0L) return 0f
    val fillStartMs = (totalThresholdMs - fillDurationMs).coerceAtLeast(0L)
    val intoFillMs = (continuousQuietMs - fillStartMs).coerceIn(0L, fillDurationMs)
    return intoFillMs.toFloat() / fillDurationMs
}
