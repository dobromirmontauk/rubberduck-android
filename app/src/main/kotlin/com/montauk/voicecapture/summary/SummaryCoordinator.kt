package com.montauk.voicecapture.summary

import com.montauk.voicecapture.logging.RubberduckLog

/**
 * What changed on a round [SummaryCoordinator.onTick] actually did something
 * on -- mirrors [com.montauk.voicecapture.tags.TagCoordinator]'s
 * null-unless-changed contract, but [onTick] also needs to hand the caller
 * exactly what's new this round (for the `summary` event line), not just the
 * resulting full state.
 */
data class SummaryRoundResult(
    /** The full bullet list after this round -- [previousBullets] plus [added], or unchanged if this round only flipped [stale]. */
    val bullets: List<String>,
    /** Index into [bullets] of the last bullet added this round, for the UI to highlight -- null when [added] is empty. */
    val newestIndex: Int?,
    /** True when this round's API call failed and [bullets] are held over from an earlier round. */
    val stale: Boolean,
    /** Exactly the bullets that landed this round -- empty on a round that only flipped [stale], never a repeat of anything already in [bullets] before this round. Feeds the `summary` event line. */
    val added: List<String>,
)

/**
 * Owns the cadence/gating and append-only enforcement around a
 * [SummaryGenerator] (bead asn-evl), so [com.montauk.voicecapture.service.RecordingService]
 * only has to call [onTick] roughly once a second (the same ticker that
 * drives [com.montauk.voicecapture.tags.TagCoordinator.onTick]) and react to
 * a non-null result -- cadence spacing, "did the transcript actually grow",
 * "is there enough of it yet to bother," and the hard append-only merge all
 * live here, not in the service.
 *
 * [generator] is null exactly when no Anthropic key is configured (mirrors
 * [SummaryGeneratorFactory]) -- [onTick] then unconditionally no-ops on
 * every call, so "keyless means summaries are silently off" holds without
 * this class needing its own key check.
 */
class SummaryCoordinator(
    private val generator: SummaryGenerator?,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var bullets: List<String> = emptyList()
    private var stale: Boolean = false

    // Bead asn-rrw: every bullet the user has swiped away this session --
    // discardedOriginal preserves the exact text (oldest first) for the next
    // round's generator context (see SummaryGenerator.generate's KDoc);
    // discardedNormalized is AppendOnlyBulletMerge's own key shape, kept as a
    // set for O(1) membership checks and reused as the hard backstop filter
    // in onSuccessfulRound below.
    private val discardedOriginal = mutableListOf<String>()
    private val discardedNormalized = mutableSetOf<String>()

    // Gating state -- both are only ever advanced once a round has actually
    // been attempted (past the due-check, the growth-check, and the
    // too-short-check below), same reasoning as TagCoordinator.maybeScore's
    // lastScoredAtMs: stamping unconditionally would let an ineligible tick
    // burn the cadence slot and push the real next attempt out further than
    // it needs to be.
    private var lastAttemptAtMs: Long? = null
    private var lastAttemptTranscriptLength: Int = 0

    /**
     * [nowMs] is recording-elapsed ms (same clock [com.montauk.voicecapture.tags.TagCoordinator]'s
     * callers use); [fullTranscript] is every finalized transcript line so
     * far, joined oldest to newest -- the ENTIRE transcript, not a rolling
     * window (unlike tag scoring, a bullet summary needs to see everything
     * said, not just what's recent). Returns null whenever nothing happened:
     * not due yet, the transcript hasn't grown since the last round, the
     * transcript is still too short to be worth summarizing, or (bead
     * asn-evl failure-state note) a round ran and failed but was already
     * stale from a previous failure -- no repeat "still stale" noise.
     */
    suspend fun onTick(nowMs: Long, fullTranscript: String): SummaryRoundResult? {
        val activeGenerator = generator ?: return null
        if (!isDue(nowMs)) return null
        if (fullTranscript.length <= lastAttemptTranscriptLength) return null
        if (wordCount(fullTranscript) < MIN_TRANSCRIPT_WORDS) return null

        lastAttemptAtMs = nowMs
        lastAttemptTranscriptLength = fullTranscript.length

        // Bead asn-jht: round-level bookkeeping only -- word counts and bullet
        // counts, never fullTranscript or bullet text itself.
        RubberduckLog.i("Summary", "round_start", "atMs" to nowMs, "transcriptWords" to wordCount(fullTranscript))
        val proposed = runCatching { activeGenerator.generate(fullTranscript, bullets, discardedOriginal.toList()) }.getOrNull()
        val result = if (proposed == null) onFailedRound() else onSuccessfulRound(proposed)
        RubberduckLog.i(
            "Summary",
            "round_end",
            "success" to (proposed != null),
            "addedCount" to (result?.added?.size ?: 0),
            "totalBullets" to (result?.bullets?.size ?: bullets.size),
            "stale" to (result?.stale ?: stale),
        )
        return result
    }

    /** The bullets/newest/stale [onTick] most recently settled on -- e.g. to re-render after a UI recreation without waiting on the next round. */
    fun currentBullets(): List<String> = bullets
    fun isStale(): Boolean = stale

    /**
     * Bead asn-rrw: the user swiped left on the notes card, discarding
     * [bulletText] -- only meaningful (and only ever called) on the CURRENT
     * newest bullet, matching what the card itself was showing at swipe
     * time. Removes it from [bullets] so it's never filed (the very next
     * [SummaryMarkdownWriter] rewrite -- the caller's job, not this class's
     * -- reflects the removal), and remembers it both for
     * [AppendOnlyBulletMerge]'s hard filter and the next round's generator
     * context (see [onTick]) so it is never regenerated. Returns false
     * (no-op) if [bulletText] doesn't match today's newest bullet -- a
     * stale/late discard tap racing a newer round landing first should not
     * silently delete the wrong entry.
     */
    fun discardBullet(bulletText: String): Boolean {
        val last = bullets.lastOrNull() ?: return false
        if (AppendOnlyBulletMerge.normalize(last) != AppendOnlyBulletMerge.normalize(bulletText)) return false
        bullets = bullets.dropLast(1)
        discardedOriginal += bulletText
        discardedNormalized += AppendOnlyBulletMerge.normalize(bulletText)
        // Bead asn-jht: length only, never the bullet text itself.
        RubberduckLog.i("Summary", "discard", "bulletLength" to bulletText.length, "remainingBullets" to bullets.size)
        return true
    }

    private fun isDue(nowMs: Long): Boolean = lastAttemptAtMs?.let { nowMs - it >= intervalMs } ?: true

    /** Bead asn-evl: API failure keeps the last good bullets and exposes staleness -- never fakes content. Returns null (nothing changed) if already stale from a previous round's failure. */
    private fun onFailedRound(): SummaryRoundResult? {
        if (stale) return null
        stale = true
        return SummaryRoundResult(bullets = bullets, newestIndex = null, stale = true, added = emptyList())
    }

    private fun onSuccessfulRound(proposed: List<String>): SummaryRoundResult? {
        val merged = AppendOnlyBulletMerge.merge(bullets, proposed, discardedNormalized)
        val added = merged.drop(bullets.size)
        val changed = added.isNotEmpty() || stale
        bullets = merged
        stale = false
        if (!changed) return null
        return SummaryRoundResult(
            bullets = merged,
            newestIndex = if (added.isNotEmpty()) merged.lastIndex else null,
            stale = false,
            added = added,
        )
    }

    private fun wordCount(text: String): Int = text.trim().split(WHITESPACE_REGEX).count { it.isNotBlank() }

    companion object {
        // Bead asn-evl's spec: "every ~60s ... only while recording."
        const val DEFAULT_INTERVAL_MS = 60_000L

        // Below this, there's not enough substance yet for a bullet summary
        // to say anything real -- skip quietly rather than spend a call (and
        // risk the model inventing content) on a few words of transcript.
        // An order of magnitude above TooShortPolicy.TOO_SHORT_WORDS, which
        // answers a different question (was this whole session worth
        // keeping at all), not "is there enough here yet for a summary."
        const val MIN_TRANSCRIPT_WORDS = 20

        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
