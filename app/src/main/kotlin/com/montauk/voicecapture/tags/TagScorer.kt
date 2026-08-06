package com.montauk.voicecapture.tags

/**
 * Pluggable MAJOR-topic scoring for [TagTracker]. Implementations turn a
 * rolling window of the live transcript into a ranked-but-unfiltered list of
 * candidate topics with a 0.0..1.0 confidence -- [TagTracker] owns all
 * hysteresis/display decisions downstream of this.
 *
 * A failing or slow scorer must never affect the audio/STT pipeline: callers
 * invoke [score] from their own coordinator coroutine, never from
 * [com.montauk.voicecapture.audio.AudioEngine]'s capture thread.
 */
interface TagScorer {
    /**
     * Minimum wall-clock spacing between calls a coordinator should respect
     * for this scorer -- cheap local heuristics can run on nearly every final
     * transcript line; a paid LLM call should run far less often.
     */
    val minIntervalMs: Long

    /**
     * [transcriptTail] is a rolling window of recent final transcript text
     * (oldest to newest); [currentCandidates] are the tag texts
     * [TagTracker] is currently tracking, given as light continuity context
     * so a scorer can prefer sticking with an existing label over minting a
     * near-duplicate synonym. [tree] is the vault's current tag hierarchy
     * (bead vn-edu.47) -- [TagTree.EMPTY] when no vault is configured, in
     * which case a scorer's output is plain free-form text exactly as
     * before this bead. When [tree] is non-empty, a scorer should match
     * speech against its existing nodes first ([TagCandidate.tagId] set,
     * [TagCandidate.tag] the matched node's leaf name) and set
     * [TagCandidate.isProposal] on a genuinely new tag only when nothing in
     * [tree] fits -- see [TagCandidateGating] for the "at most one
     * proposal" backstop. Must not throw -- scoring failures are the
     * caller's concern (typically: degrade to a fallback scorer), and a
     * scorer that can fail should catch its own exceptions and return
     * `emptyList()` instead.
     */
    suspend fun score(transcriptTail: String, currentCandidates: List<String>, tree: TagTree): List<TagCandidate>
}
