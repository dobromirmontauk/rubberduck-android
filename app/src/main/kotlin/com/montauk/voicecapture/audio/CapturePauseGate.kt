package com.montauk.voicecapture.audio

/**
 * How [AudioEngine]'s capture loop should currently treat captured PCM.
 * Named independently of [com.montauk.voicecapture.service.RecordingActivityState]
 * (which additionally distinguishes SPEAKING/QUIET while not paused at all)
 * -- [AudioEngine] only ever needs to know "persist / buffer / drop", not
 * the richer UI-facing activity state.
 */
enum class AudioPauseMode {
    /** Normal capture: every frame is persisted (fed to the encoder/WAL). */
    ACTIVE,

    /** Soft (auto) pause: nothing persisted, frames buffered in a ring instead. */
    SOFT,

    /** Hard (manual) pause: nothing persisted, nothing buffered -- drop-at-source, no retained buffer. */
    HARD,
}

/**
 * Pure decision logic (bead asn-r60) for what [AudioEngine.runCaptureLoop]
 * should do with each captured PCM chunk under the current [AudioPauseMode]
 * -- deliberately free of any `android.media` dependency so the feature's
 * core "pause drops audio" / "hard vs. soft buffer" / "ring-buffer prepend
 * on auto-resume" behavior is covered by plain JVM unit tests. Real Opus
 * encoding (`MediaCodec`) has no such test story in this codebase (no
 * existing `AudioEngineTest` -- see README), so [AudioEngine] itself stays a
 * thin Android-only orchestrator that turns [Decision.toPersist] into actual
 * encoder feeds; this class is where the actual pause semantics live and get
 * verified.
 *
 * [onFrame] is called once per captured chunk; [setMode] is called on every
 * pause-mode transition and may itself return chunks to persist (the
 * ring-buffer flush). Only a transition INTO [AudioPauseMode.ACTIVE] out of
 * [AudioPauseMode.SOFT] flushes the ring buffer -- escalating straight from
 * SOFT to HARD (the pause button tapped while already auto-paused) discards
 * the tentative buffer instead of persisting it, matching "hard mute means
 * nothing retained" even when a soft pause was already in flight.
 */
class CapturePauseGate(ringBufferCapacityBytes: Int) {
    private val ringBuffer = PcmRingBuffer(ringBufferCapacityBytes)

    var mode: AudioPauseMode = AudioPauseMode.ACTIVE
        private set

    data class Decision(val toPersist: List<ByteArray>)

    private val noOpDecision = Decision(emptyList())

    /** Feed one captured chunk; returns what should now be fed to the encoder/WAL, in order (usually just this chunk, or nothing). */
    fun onFrame(pcm: ByteArray, length: Int): Decision = when (mode) {
        AudioPauseMode.ACTIVE -> Decision(listOf(pcm.copyOf(length)))
        AudioPauseMode.SOFT -> {
            ringBuffer.write(pcm.copyOf(length))
            noOpDecision
        }
        AudioPauseMode.HARD -> noOpDecision
    }

    /** Transitions to [newMode]; returns any ring-buffered chunks (oldest-first) that must now be persisted. */
    fun setMode(newMode: AudioPauseMode): Decision {
        val flushed = if (mode == AudioPauseMode.SOFT && newMode == AudioPauseMode.ACTIVE) {
            ringBuffer.drainOrdered()
        } else {
            emptyList()
        }
        if (newMode != AudioPauseMode.SOFT) ringBuffer.clear()
        mode = newMode
        return Decision(flushed)
    }

    /** Call at the start of every new recording session so a previous session's buffered audio never leaks in. */
    fun reset() {
        ringBuffer.clear()
        mode = AudioPauseMode.ACTIVE
    }
}
