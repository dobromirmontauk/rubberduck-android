package com.montauk.voicecapture.tags

import kotlin.math.pow

/** One scored candidate topic, as returned by a [TagScorer]. Confidence is 0.0..1.0. */
data class TagCandidate(val tag: String, val confidence: Double)

/** Rendered chip size class, driven purely by [DisplayedTag.confidence] -- see [TagTier.forConfidence]. */
enum class TagTier {
    PRIMARY, SECONDARY, TERTIARY;

    companion object {
        // Deliberately coarse (3 buckets, bead vn-edu.38 spec: "3 tiers ok")
        // rather than a continuous font-size formula -- keeps the UI mapping
        // trivially testable and avoids the chips row visibly jittering on
        // every 0.01 confidence wobble between scorer calls.
        private const val PRIMARY_MIN = 0.75
        private const val SECONDARY_MIN = 0.5

        fun forConfidence(confidence: Double): TagTier = when {
            confidence >= PRIMARY_MIN -> PRIMARY
            confidence >= SECONDARY_MIN -> SECONDARY
            else -> TERTIARY
        }
    }
}

/** One currently-displayed tag: 1-based [rank] (1 = most confident), [tier] for chip size. */
data class DisplayedTag(val tag: String, val confidence: Double, val rank: Int, val tier: TagTier)

/**
 * Confidence-ranked MAJOR-topic tracker (bead vn-edu.38, supersedes the v1
 * frequency-chip cloud in `topics/TopicCloud.kt`). Pure Kotlin, no Android
 * dependency, no I/O -- callers feed it [TagScorer] output and get back the
 * up-to-3-tag displayed set; scoring itself (network, heuristics) lives
 * entirely outside this class.
 *
 * Internally tracks up to [maxCandidates] candidates (by normalized tag
 * text) with a confidence and a last-updated timestamp, decaying anything
 * not present in the latest scored batch. Displaying is governed by
 * hysteresis so tags don't flap on every scorer call:
 *  - **Entry** is hard: a candidate not currently displayed must clear
 *    [entryThresholds] for the rank slot it would occupy -- slot 1 (primary)
 *    is a real bar, slots 2/3 are a *very* high bar, per spec ("VERY high
 *    bar for showing a 2nd/3rd tag").
 *  - **Exit** is soft: once displayed, a tag only needs to stay above
 *    [exitThreshold] (much lower than entry) to keep its slot, and even
 *    then only after it has been displayed for at least [minDwellMs] --
 *    within the dwell window it survives regardless of confidence.
 *  - **Decay**: any tracked candidate not present in a given [score] call
 *    has its confidence multiplied by an exponential decay factor scaled by
 *    elapsed time, so a topic the conversation has moved on from fades out
 *    on its own rather than sitting at a stale high confidence forever.
 *
 * Not thread-safe -- callers own serializing access (the coordinator that
 * owns this drives it from a single coroutine, matching
 * [com.montauk.voicecapture.session.RecordingModeStateMachine]'s single-owner
 * pattern).
 */
class TagTracker(
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    private val maxDisplayed: Int = DEFAULT_MAX_DISPLAYED,
    private val entryThresholds: List<Double> = DEFAULT_ENTRY_THRESHOLDS,
    private val exitThreshold: Double = DEFAULT_EXIT_THRESHOLD,
    private val minDwellMs: Long = DEFAULT_MIN_DWELL_MS,
    private val decayHalfLifeMs: Long = DEFAULT_DECAY_HALF_LIFE_MS,
) {
    init {
        require(entryThresholds.size >= maxDisplayed) {
            "need one entry threshold per displayable slot (${entryThresholds.size} < $maxDisplayed)"
        }
    }

    private data class Candidate(var tag: String, var confidence: Double, var lastUpdatedMs: Long)
    private data class Displayed(var displayedSinceMs: Long)

    /** Keyed by normalized (trimmed, lowercased) tag text so scorer casing drift doesn't split one topic into two. */
    private val candidates = LinkedHashMap<String, Candidate>()
    private val displayed = LinkedHashMap<String, Displayed>()

    /** Current displayed set without feeding new scores -- e.g. for re-rendering after a mode change. */
    fun current(): List<DisplayedTag> = renderDisplayed()

    /** Test-only visibility into the "store ~10" side of "store 10, show 3". */
    internal fun trackedCandidateCount(): Int = candidates.size

    /**
     * Feeds a fresh scorer result at [nowMs] and returns the (possibly
     * unchanged) displayed set. Candidates absent from [scored] decay
     * in-place rather than vanishing immediately.
     */
    fun onScored(scored: List<TagCandidate>, nowMs: Long): List<DisplayedTag> {
        val seenKeys = mutableSetOf<String>()
        for (candidate in scored) {
            val text = candidate.tag.trim()
            if (text.isEmpty()) continue
            val key = normalize(text)
            seenKeys += key
            val confidence = candidate.confidence.coerceIn(0.0, 1.0)
            val existing = candidates[key]
            if (existing == null) {
                candidates[key] = Candidate(text, confidence, nowMs)
            } else {
                existing.tag = text
                existing.confidence = confidence
                existing.lastUpdatedMs = nowMs
            }
        }
        decayUnseen(seenKeys, nowMs)
        trimToMaxCandidates()
        return recomputeDisplayed(nowMs)
    }

    /** Re-evaluates decay/dwell/exit at [nowMs] without new scorer input -- lets stale tags fade between scorer calls. */
    fun tick(nowMs: Long): List<DisplayedTag> {
        decayUnseen(emptySet(), nowMs)
        return recomputeDisplayed(nowMs)
    }

    private fun decayUnseen(seenKeys: Set<String>, nowMs: Long) {
        for (candidate in candidates.values) {
            val key = normalize(candidate.tag)
            if (key in seenKeys) continue
            val elapsedMs = (nowMs - candidate.lastUpdatedMs).coerceAtLeast(0L)
            if (elapsedMs == 0L) continue
            val decayFactor = 0.5.pow(elapsedMs.toDouble() / decayHalfLifeMs.toDouble())
            candidate.confidence *= decayFactor
            candidate.lastUpdatedMs = nowMs
        }
    }

    private fun trimToMaxCandidates() {
        if (candidates.size <= maxCandidates) return
        // Never drop a currently-displayed tag here -- exit is governed
        // solely by recomputeDisplayed's hysteresis, not by losing the
        // internal bookkeeping slot out from under it.
        val droppable = candidates.entries
            .filter { it.key !in displayed }
            .sortedBy { it.value.confidence }
        val overBy = candidates.size - maxCandidates
        droppable.take(overBy).forEach { candidates.remove(it.key) }
    }

    private fun recomputeDisplayed(nowMs: Long): List<DisplayedTag> {
        // Sticky tags: still displayed regardless of rank re-shuffling, as
        // long as dwell hasn't elapsed yet or confidence hasn't dropped
        // below the (low) exit bar.
        val sticky = displayed.keys.filter { key ->
            val candidate = candidates[key] ?: return@filter false
            val dwellElapsed = nowMs - displayed.getValue(key).displayedSinceMs >= minDwellMs
            !dwellElapsed || candidate.confidence >= exitThreshold
        }.toMutableSet()

        val ranked = candidates.entries.sortedByDescending { it.value.confidence }
        val newDisplayOrder = mutableListOf<String>()
        for (entry in ranked) {
            if (newDisplayOrder.size >= maxDisplayed) break
            val key = entry.key
            val rankSlot = newDisplayOrder.size // 0-based slot this candidate would take if admitted
            val eligible = key in sticky || entry.value.confidence >= entryThresholds[rankSlot]
            if (eligible) newDisplayOrder += key
        }

        // Any sticky tag that fell out of the pool entirely (e.g. trimmed
        // candidate map) should not still claim a slot -- newDisplayOrder
        // above already only draws from `candidates`, so this is just bookkeeping.
        val nowDisplayedSet = newDisplayOrder.toSet()
        for (key in displayed.keys.toList()) {
            if (key !in nowDisplayedSet) displayed.remove(key)
        }
        for (key in newDisplayOrder) {
            if (key !in displayed) displayed[key] = Displayed(displayedSinceMs = nowMs)
        }

        return newDisplayOrder.mapIndexed { index, key ->
            val candidate = candidates.getValue(key)
            DisplayedTag(
                tag = candidate.tag,
                confidence = candidate.confidence,
                rank = index + 1,
                tier = TagTier.forConfidence(candidate.confidence),
            )
        }
    }

    private fun renderDisplayed(): List<DisplayedTag> =
        displayed.keys.mapNotNull { key -> candidates[key]?.let { key to it } }
            .sortedByDescending { it.second.confidence }
            .mapIndexed { index, (_, candidate) ->
                DisplayedTag(candidate.tag, candidate.confidence, index + 1, TagTier.forConfidence(candidate.confidence))
            }

    private fun normalize(tag: String): String = tag.trim().lowercase()

    companion object {
        const val DEFAULT_MAX_CANDIDATES = 10
        const val DEFAULT_MAX_DISPLAYED = 3
        // Slot 1 (primary) is a real but reachable bar; slots 2/3 are a VERY
        // high bar per spec -- entering a 2nd/3rd chip should be rare, not
        // "whatever's next in line."
        val DEFAULT_ENTRY_THRESHOLDS = listOf(0.55, 0.82, 0.88)
        const val DEFAULT_EXIT_THRESHOLD = 0.3
        const val DEFAULT_MIN_DWELL_MS = 8_000L
        const val DEFAULT_DECAY_HALF_LIFE_MS = 45_000L
    }
}
