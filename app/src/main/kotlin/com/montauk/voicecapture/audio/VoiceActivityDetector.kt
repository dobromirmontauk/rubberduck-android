package com.montauk.voicecapture.audio

/**
 * Pure, audio-energy-threshold voice-activity detector (bead asn-r60):
 * classifies each incoming normalized-RMS window (the same ~100ms windows
 * [MicLevelMeter] already emits for the loudness bar) as speech or quiet,
 * and tracks how long the signal has been continuously quiet.
 * [com.montauk.voicecapture.service.RecordingService] uses [continuousQuietMs]
 * against the (settings-tunable) auto-pause threshold to decide when to
 * enter [com.montauk.voicecapture.service.RecordingActivityState.AUTO_PAUSED],
 * and [isSpeaking] flipping back to true to decide when to auto-resume.
 *
 * Deliberately a simple energy threshold rather than a spectral/ML VAD or an
 * `android.media` built-in: the framework has no dedicated on-device VAD API
 * (`MediaCodec`/`AudioRecord` don't classify speech vs. silence themselves),
 * and reusing the RMS windowing [MicLevelMeter] already computes avoids a
 * second audio-analysis pass or a new dependency. Same threshold value and
 * spirit as [com.montauk.voicecapture.service.SilenceDetector]'s existing
 * "(silence)" transcript hint, but audio-only (no transcript-activity input)
 * so it works identically offline/keyless -- exactly the property the
 * auto-pause feature needs, since it must trim quiet spans regardless of
 * whether STT is even configured.
 *
 * No hysteresis on [isSpeaking] itself: a single loud window immediately
 * flips it true (auto-resume should feel instant -- the ring buffer already
 * covers the few seconds of onset before this fires) and any loud window
 * resets [continuousQuietMs] to zero (a real 30s-of-silence threshold should
 * mean *any* sound restarts the countdown, not just sustained loudness).
 */
class VoiceActivityDetector(
    private val speechThreshold: Float = DEFAULT_SPEECH_THRESHOLD,
) {
    var isSpeaking: Boolean = false
        private set

    var continuousQuietMs: Long = 0L
        private set

    /** Feed one normalized (0f..1f) RMS window, e.g. from [MicLevelMeter.onWindow]. */
    fun onWindow(rms: Float, windowMs: Long) {
        val speaking = rms >= speechThreshold
        isSpeaking = speaking
        continuousQuietMs = if (speaking) 0L else continuousQuietMs + windowMs
    }

    /** Call when a new recording session starts, so a previous session's state doesn't leak in. */
    fun reset() {
        isSpeaking = false
        continuousQuietMs = 0L
    }

    companion object {
        const val DEFAULT_SPEECH_THRESHOLD = 0.02f
    }
}
