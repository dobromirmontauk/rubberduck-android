package com.montauk.voicecapture.service

/**
 * Bead vn-edu.56: pure decision of whether a just-stopped session is "too
 * short to save" and should be discarded by default (offering the user a
 * "Save anyway" override via [TooShortWarningStateHolder]/[TooShortDecision])
 * rather than finalized normally.
 *
 * Two independent triggers, either one is sufficient to discard:
 *  (a) the whole session ran under [TOO_SHORT_SECONDS] -- almost certainly an
 *      accidental tap, regardless of whether STT was ever wired up.
 *  (b) live STT was actively connected at some point during the session AND
 *      the final transcript it produced has fewer than [TOO_SHORT_WORDS]
 *      words -- a long recording STT genuinely listened to but that never
 *      amounted to anything worth keeping.
 *
 * CRITICAL (GOAL.md: "audio is never lost"): when STT was never connected at
 * all -- offline/keyless build, or STT simply never got a chance to connect
 * -- trigger (b) must NOT apply on its own. A 15-minute offline session with
 * zero live transcript is completely normal (the ingest pipeline transcribes
 * it later) and must be saved, not discarded. [sttWasConnected] is what gates
 * that: it must be true only if the connection was ever actually established
 * during the session (not just "is connected right now"), so a session that
 * connected then dropped mid-way still gets word-count-checked rather than
 * treated as if it had been offline all along.
 */
object TooShortPolicy {
    /** A session shorter than this, regardless of STT/transcript, is discarded by default. */
    const val TOO_SHORT_SECONDS: Long = 10L

    /** Below this many words in the final live transcript -- only checked when STT was ever connected (see class KDoc). */
    const val TOO_SHORT_WORDS: Int = 5

    /**
     * How long the "Session too short to save" warning stays actionable
     * before defaulting to discard -- shared by [RecordingService] (the real
     * timeout) and the UI snackbar (which dismisses itself once
     * [TooShortWarningStateHolder] clears, so it never needs to duplicate
     * this number on its own timer).
     */
    const val WARNING_WINDOW_MS: Long = 6_000L

    /** True when [durationMs]/[sttWasConnected]/[finalWordCount] should discard this session by default. See class KDoc for the exact rule. */
    fun shouldDiscard(durationMs: Long, sttWasConnected: Boolean, finalWordCount: Int): Boolean {
        val tooShortDuration = durationMs < TOO_SHORT_SECONDS * 1_000L
        val tooFewWords = sttWasConnected && finalWordCount < TOO_SHORT_WORDS
        return tooShortDuration || tooFewWords
    }
}
