package com.montauk.voicecapture.tags

import kotlin.math.pow

/**
 * One scored candidate topic, as returned by a [TagScorer]. Confidence is
 * 0.0..1.0. Bead vn-edu.47: [tagId] is set when [tag] was matched to an
 * existing node in the vault's tag tree (the vault's ULID-prefixed
 * `tags.yaml` id) -- null for plain free-form text, either because no
 * vault tree was available to match against or because the scorer is
 * proposing a genuinely new tag ([isProposal] true) that clearly fits
 * nothing in the tree.
 */
data class TagCandidate(val tag: String, val confidence: Double, val tagId: String? = null, val isProposal: Boolean = false)

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

/**
 * One currently-displayed tag: 1-based [rank] (1 = most confident), [tier]
 * for chip size. [tagId]/[isProposal] carry [TagCandidate]'s same-named
 * fields through the tracker's hysteresis/decay bookkeeping unchanged --
 * see [TagCandidate]'s KDoc for what they mean.
 */
data class DisplayedTag(
    val tag: String,
    val confidence: Double,
    val rank: Int,
    val tier: TagTier,
    val tagId: String? = null,
    val isProposal: Boolean = false,
)

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
 *
 * Bead vn-edu.46: every [onScored] call also runs [suppressOverlappingCandidates],
 * which merges/drops substring- or high-word-overlap candidate pairs (e.g.
 * "candidate" + "candidate might" -- one topic scored as two slightly
 * different phrasings) so one real topic never occupies two display slots at
 * once. Scorer-agnostic -- prefix-word containment only, no vocabulary/
 * stopword knowledge -- see [isHighOverlap].
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

    private data class Candidate(
        var tag: String,
        var confidence: Double,
        var lastUpdatedMs: Long,
        var tagId: String? = null,
        var isProposal: Boolean = false,
    )
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
                candidates[key] = Candidate(text, confidence, nowMs, candidate.tagId, candidate.isProposal)
            } else {
                existing.tag = text
                existing.confidence = confidence
                existing.lastUpdatedMs = nowMs
                existing.tagId = candidate.tagId
                existing.isProposal = candidate.isProposal
            }
        }
        decayUnseen(seenKeys, nowMs)
        suppressOverlappingCandidates()
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

    /**
     * Bead vn-edu.46: merges/suppresses substring- or high-overlap candidate
     * pairs so one topic never occupies two slots at once (e.g. "candidate"
     * + "candidate might" both scoring, one topic, two chips). Deliberately
     * scorer-agnostic -- prefix-word containment only, no stopword list, no
     * part-of-speech guessing -- so it applies equally regardless of which
     * [TagScorer] produced the candidates (per the bead's superseding
     * decision, that's [AnthropicTagScorer] only now, but the fix itself
     * doesn't know or care). Of an overlapping pair, keeps the higher-
     * confidence phrasing and drops the other from [candidates] entirely
     * (not just from display) so it can't come back next tick without a
     * fresh, independent score.
     */
    private fun suppressOverlappingCandidates() {
        val keys = candidates.keys.toList()
        val suppressed = mutableSetOf<String>()
        for (i in keys.indices) {
            val keyA = keys[i]
            if (keyA in suppressed) continue
            val candidateA = candidates[keyA] ?: continue
            for (j in i + 1 until keys.size) {
                val keyB = keys[j]
                if (keyB in suppressed) continue
                val candidateB = candidates[keyB] ?: continue
                if (!isHighOverlap(candidateA.tag, candidateB.tag)) continue
                if (candidateA.confidence >= candidateB.confidence) {
                    suppressed += keyB
                } else {
                    // keyA just lost to keyB -- stop comparing it against any
                    // further candidates in this pass rather than letting an
                    // already-suppressed "survivor" keep suppressing others.
                    suppressed += keyA
                    break
                }
            }
        }
        for (key in suppressed) {
            candidates.remove(key)
            displayed.remove(key)
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
                tagId = candidate.tagId,
                isProposal = candidate.isProposal,
            )
        }
    }

    private fun renderDisplayed(): List<DisplayedTag> =
        displayed.keys.mapNotNull { key -> candidates[key]?.let { key to it } }
            .sortedByDescending { it.second.confidence }
            .mapIndexed { index, (_, candidate) ->
                DisplayedTag(
                    tag = candidate.tag,
                    confidence = candidate.confidence,
                    rank = index + 1,
                    tier = TagTier.forConfidence(candidate.confidence),
                    tagId = candidate.tagId,
                    isProposal = candidate.isProposal,
                )
            }

    private fun normalize(tag: String): String = tag.trim().lowercase()

    /**
     * True when the shorter phrase's *whole word sequence* is exactly the
     * longer phrase's leading words -- e.g. "candidate" vs. "candidate
     * might" (["candidate"] is ["candidate", "might"]'s prefix), or "kitchen
     * remodel" vs. "kitchen remodel budget". Deliberately a prefix check,
     * not "do these two phrases share any word" (a looser word-set-subset
     * rule): a single shared *trailing* word is common between genuinely
     * unrelated topics by coincidence (e.g. two distinct fixture tags named
     * "topic" and "second topic" share the word "topic" but describe
     * nothing alike) and would false-positive under a looser rule --
     * confirmed against this exact TagCoordinatorTest fixture. Requiring the
     * match to start at the front captures the bead's actual bug pattern
     * (a real topic word with a modal/filler word trailing it) without that
     * false-positive class. Word-boundary-aware, not raw substring, so e.g.
     * "cab" is never treated as overlapping "candidate" just because one
     * string contains the other's characters.
     */
    private fun isHighOverlap(a: String, b: String): Boolean {
        val wordsA = wordList(a)
        val wordsB = wordList(b)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val (shorter, longer) = if (wordsA.size <= wordsB.size) wordsA to wordsB else wordsB to wordsA
        if (shorter.size == longer.size) return shorter == longer
        return longer.subList(0, shorter.size) == shorter
    }

    private fun wordList(text: String): List<String> =
        text.lowercase().split(WORD_BOUNDARY).filter { it.isNotBlank() }

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
        private val WORD_BOUNDARY = Regex("\\s+")
    }
}
