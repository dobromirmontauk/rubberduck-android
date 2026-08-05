package com.montauk.voicecapture.service

/**
 * Decides when to show the "(silence)" hint in the recording screen's
 * transcript pane. Pure state machine (elapsed-time inputs only, no Android
 * dependency) so it's covered by plain JVM unit tests; [RecordingService]
 * feeds it wall-clock (`SystemClock.elapsedRealtime()`) ticks from both the
 * STT partial/final stream and the mic-level meter.
 *
 * Deliberately ignores STT connection state: a session with no AssemblyAI
 * key configured never receives any partial/final, so time-since-last-
 * activity grows unbounded from the start of the recording and the mic-RMS
 * condition alone ends up deciding visibility -- exactly the "regardless of
 * socket state, offline too" requirement.
 */
class SilenceDetector(
    private val silenceTimeoutMs: Long = 4_000L,
    private val rmsThreshold: Float = 0.02f,
) {
    private var lastActivityMs: Long? = null

    /** Call whenever a partial or final transcript segment arrives with non-blank text. */
    fun onTranscriptActivity(atMs: Long) {
        lastActivityMs = atMs
    }

    /** Call when a new recording session starts, so a previous session's activity doesn't leak in. */
    fun reset() {
        lastActivityMs = null
    }

    /** [recordingStartMs] is the activity baseline until the first transcript segment ever arrives. */
    fun isSilent(nowMs: Long, recordingStartMs: Long, currentRms: Float): Boolean {
        val since = nowMs - (lastActivityMs ?: recordingStartMs)
        return since >= silenceTimeoutMs && currentRms < rmsThreshold
    }
}
