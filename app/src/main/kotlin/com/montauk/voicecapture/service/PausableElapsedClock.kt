package com.montauk.voicecapture.service

/**
 * A wall-clock-driven elapsed-time reading that holds steady across a
 * [pause]/[resume] span rather than continuing to grow (bead asn-r60) --
 * i.e. behaves like a stopwatch's pause button, not a live "time since X"
 * display. Pure Kotlin, driven entirely by caller-supplied
 * `SystemClock.elapsedRealtime()`-style readings so it's unit-testable
 * without any Android dependency.
 *
 * [RecordingService] uses two independently-paused instances of this class:
 *  - one paused only for [com.montauk.voicecapture.service.RecordingActivityState.USER_PAUSED]
 *    (hard pause), driving [RecordingUiState.elapsedMs] -- the on-screen big
 *    timer and the foreground notification -- so it visibly freezes exactly
 *    when the bead's spec says it should ("timer freezes with explicit
 *    paused indicator"). Auto-pause deliberately does NOT pause this one:
 *    the feature is meant to feel seamless/background, not like the
 *    recording itself stopped.
 *  - one paused for BOTH [com.montauk.voicecapture.service.RecordingActivityState.USER_PAUSED]
 *    and [com.montauk.voicecapture.service.RecordingActivityState.AUTO_PAUSED]
 *    ("audioTimelineClock") -- this is the trimmed-audio-timeline basis for
 *    `live-transcript.jsonl`'s mode/pause/resume event `t_ms` values, and for
 *    `meta.json`'s new `recorded_ms` field: it excludes every span whose
 *    audio was never persisted, exactly matching `audio.ogg`'s own duration
 *    (see [AudioEngine]'s cumulative-fed-bytes presentation timestamps,
 *    which give the persisted file that same trimmed basis independently).
 */
class PausableElapsedClock {
    private var pausedAccumMs: Long = 0L
    private var pauseStartedAtMs: Long? = null

    /** No-op if already paused. */
    fun pause(nowMs: Long) {
        if (pauseStartedAtMs == null) pauseStartedAtMs = nowMs
    }

    /** No-op if not currently paused. */
    fun resume(nowMs: Long) {
        val startedAt = pauseStartedAtMs ?: return
        pausedAccumMs += (nowMs - startedAt)
        pauseStartedAtMs = null
    }

    fun isPaused(): Boolean = pauseStartedAtMs != null

    /**
     * [rawElapsedMs] is the caller's own raw wall-clock-since-session-start
     * reading (e.g. `SystemClock.elapsedRealtime() - startElapsedRealtimeMs`);
     * this subtracts every completed and (if currently paused) in-progress
     * paused span from it.
     */
    fun elapsedMs(nowMs: Long, rawElapsedMs: Long): Long {
        val currentlyPausedMs = pauseStartedAtMs?.let { nowMs - it } ?: 0L
        return rawElapsedMs - pausedAccumMs - currentlyPausedMs
    }

    /** Call at the start of every new recording session so a previous session's paused time never leaks in. */
    fun reset() {
        pausedAccumMs = 0L
        pauseStartedAtMs = null
    }
}
