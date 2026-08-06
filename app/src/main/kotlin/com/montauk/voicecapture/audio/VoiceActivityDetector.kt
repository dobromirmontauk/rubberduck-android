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
 * flips it true and any loud window resets [continuousQuietMs] to zero (a
 * real 10s-of-silence threshold should mean *any* sound restarts the
 * countdown, not just sustained loudness). [isSpeaking] alone is still what
 * drives the live SPEAKING/QUIET signal outside of any pause.
 *
 * [consecutiveSpeechWindows] (bead asn-o63) exists for a narrower purpose:
 * resuming FROM an auto-pause must require a couple of consecutive speaking
 * windows, not a single one. Live-test evidence (session 1458_reb8) showed
 * a 323ms auto-pause -- entered, then immediately resumed on the very next
 * ~100ms window -- because the original design gated the resume decision on
 * bare [isSpeaking]. This counter increments only while consecutive windows
 * are all speaking and resets to 0 on any quiet window, so a caller can
 * require e.g. 3 consecutive windows (~300ms of sustained speech) before
 * treating a blip as "actually resuming." The ring-buffer prepend this
 * gates ([AudioEngine]'s soft-pause ring buffer) is unaffected by this
 * delay: it keeps buffering every window regardless of hysteresis, so by
 * the time the hysteresis threshold is satisfied, the buffer still holds
 * audio from the *first* blip window onward -- nothing clips.
 */
class VoiceActivityDetector(
    private val speechThreshold: Float = DEFAULT_SPEECH_THRESHOLD,
) {
    var isSpeaking: Boolean = false
        private set

    var continuousQuietMs: Long = 0L
        private set

    var consecutiveSpeechWindows: Int = 0
        private set

    /** Feed one normalized (0f..1f) RMS window, e.g. from [MicLevelMeter.onWindow]. */
    fun onWindow(rms: Float, windowMs: Long) {
        val speaking = rms >= speechThreshold
        isSpeaking = speaking
        if (speaking) {
            continuousQuietMs = 0L
            consecutiveSpeechWindows += 1
        } else {
            continuousQuietMs += windowMs
            consecutiveSpeechWindows = 0
        }
    }

    /** Call when a new recording session starts, so a previous session's state doesn't leak in. */
    fun reset() {
        isSpeaking = false
        continuousQuietMs = 0L
        consecutiveSpeechWindows = 0
    }

    companion object {
        const val DEFAULT_SPEECH_THRESHOLD = 0.02f
    }
}
