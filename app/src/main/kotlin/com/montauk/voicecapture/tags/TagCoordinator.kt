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

        val due = scorer.minIntervalMs
        val last = lastScoredAtMs
        if (last != null && endMs - last < due) return null
        lastScoredAtMs = endMs

        val tail = lines.joinToString(" ") { it.text }
        if (tail.isBlank()) return null

        val before = tracker.current()
        val currentTags = before.map { it.tag }
        val scored = runCatching { scorer.score(tail, currentTags) }.getOrDefault(emptyList())
        val after = tracker.onScored(scored, endMs)
        return after.takeIf { it != before }
    }

    /** Lets stale tags decay/exit between scorer calls (e.g. driven by the same ticker that already drives the elapsed-time clock). Same null-unless-changed contract as [onFinalLine]. */
    fun tick(nowMs: Long): List<DisplayedTag>? {
        val before = tracker.current()
        val after = tracker.tick(nowMs)
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
