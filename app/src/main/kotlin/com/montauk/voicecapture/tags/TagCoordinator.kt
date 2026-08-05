package com.montauk.voicecapture.tags

/**
 * Owns the rolling-transcript-tail bookkeeping and scorer-cadence timing
 * around a [TagTracker], so [com.montauk.voicecapture.service.RecordingService]
 * only has to call [onFinalLine] as new final transcript segments arrive and
 * react when it returns a new displayed-tags set -- "what text window to
 * send" and "how often to call the scorer" live here, not in the service.
 *
 * A [TagScorer] failing or being slow never blocks [onFinalLine]'s caller
 * beyond that one scorer call -- [TagScorer.score] is documented to degrade
 * to a fallback rather than throw, and this class defensively no-ops on top
 * of that (`runCatching`) in case a caller-supplied scorer doesn't honor it.
 */
class TagCoordinator(
    private val scorer: TagScorer,
    private val tracker: TagTracker = TagTracker(),
    private val rollingTailWindowMs: Long = DEFAULT_ROLLING_TAIL_WINDOW_MS,
) {
    private data class TimedText(val text: String, val endMs: Long)

    private val lines = mutableListOf<TimedText>()
    private var lastScoredAtMs: Long? = null

    /** The tracker's current displayed set, e.g. to re-render after a UI recreation without waiting on the next scorer call. */
    fun currentDisplayed(): List<DisplayedTag> = tracker.current()

    /**
     * Feeds one newly-finalized transcript line ending at [endMs] (session-
     * elapsed ms, matching [com.montauk.voicecapture.service.TranscriptLine.endMs]).
     * Returns the new displayed-tags set if the scorer ran *and* the
     * displayed set changed as a result; null otherwise (scorer's cadence
     * not due yet, or it ran but nothing about the display changed).
     */
    suspend fun onFinalLine(text: String, endMs: Long): List<DisplayedTag>? {
        if (text.isNotBlank()) lines += TimedText(text, endMs)
        trimOldLines(endMs)
        return maybeScore(rollingTailText(), endMs)
    }

    /**
     * Bead vn-edu.44: lets the scorer's cadence fire off recording-elapsed
     * time (the same ticker [com.montauk.voicecapture.service.RecordingService]
     * already runs every second) rather than only when a turn closes.
     * [currentPartialText] is the STT's still-open partial (may be blank,
     * e.g. mid-silence) -- folded into the rolling tail alongside already-
     * finalized [lines] so a single long monologue with no closed turn for a
     * minute still gives the scorer real text to work with; tag quality
     * tolerates partial-text noise (in-progress words correcting themselves)
     * per spec, and [TagTracker]'s entry hysteresis already guards flapping
     * from a candidate whose confidence wobbles as the partial keeps growing.
     *
     * Shares [maybeScore]'s cadence clock with [onFinalLine] -- a final line
     * landing between ticks doesn't cause a double-score, and a tick landing
     * right after a final line's own score doesn't either. When the cadence
     * isn't due yet (or the tail is blank), this still lets stale tags decay/
     * exit exactly like the older [tick] -- same null-unless-changed
     * contract throughout.
     */
    suspend fun onTick(nowMs: Long, currentPartialText: String = ""): List<DisplayedTag>? {
        trimOldLines(nowMs)
        val scored = maybeScore(rollingTailText(currentPartialText), nowMs)
        if (scored != null) return scored
        val before = tracker.current()
        val after = tracker.tick(nowMs)
        return after.takeIf { it != before }
    }

    /**
     * Decay-only variant of [onTick], predating bead vn-edu.44 -- kept for
     * callers that only want stale-tag decay/exit without also feeding the
     * scorer (and without the `suspend` that requires). Production code now
     * uses [onTick] instead, which does everything this does plus the actual
     * fix for vn-edu.44. Same null-unless-changed contract.
     */
    fun tick(nowMs: Long): List<DisplayedTag>? {
        val before = tracker.current()
        val after = tracker.tick(nowMs)
        return after.takeIf { it != before }
    }

    /** [lines] (already-finalized) plus [currentPartialText] (the still-open turn, if any), oldest to newest. */
    private fun rollingTailText(currentPartialText: String = ""): String {
        val parts = lines.map { it.text } + listOfNotNull(currentPartialText.takeIf { it.isNotBlank() })
        return parts.joinToString(" ")
    }

    /**
     * Shared cadence gate + scorer invocation for both [onFinalLine] and
     * [onTick]: due-check against [scorer]'s own [TagScorer.minIntervalMs],
     * skip (return null) if not due yet or if [tail] is blank, otherwise
     * score and feed [tracker], returning the new displayed set only if it
     * actually changed.
     */
    private suspend fun maybeScore(tail: String, nowMs: Long): List<DisplayedTag>? {
        val due = scorer.minIntervalMs
        val last = lastScoredAtMs
        if (last != null && nowMs - last < due) return null
        lastScoredAtMs = nowMs

        if (tail.isBlank()) return null

        val before = tracker.current()
        val currentTags = before.map { it.tag }
        val scored = runCatching { scorer.score(tail, currentTags) }.getOrDefault(emptyList())
        val after = tracker.onScored(scored, nowMs)
        return after.takeIf { it != before }
    }

    private fun trimOldLines(nowMs: Long) {
        lines.removeAll { nowMs - it.endMs > rollingTailWindowMs }
    }

    companion object {
        // Two minutes of rolling context per scorer call -- long enough to
        // give an LLM scorer a real paragraph of context, short enough that
        // a topic the conversation has moved well past isn't still being
        // re-fed as "current" several minutes later.
        const val DEFAULT_ROLLING_TAIL_WINDOW_MS = 120_000L
    }
}
