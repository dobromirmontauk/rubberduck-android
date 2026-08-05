package com.montauk.voicecapture.tags

/**
 * Production keyless/degrade [TagScorer] (bead vn-edu.46 superseding
 * decision, 2026-08-05): the user explicitly rejected a heuristic fallback
 * that guesses at topics without an LLM -- "candidate might" + "candidate"
 * scoring as two separate tags, modal-verb bigrams, etc. all trace back to
 * [HeuristicTagScorer] pretending to be smart with no real language
 * understanding behind it.
 *
 * This scorer never computes anything: it always returns an empty list, so
 * [TagTracker] never has anything to display. [com.montauk.voicecapture.ui.RecordingScreen]'s
 * tags slot renders the exact string "(register your API key to see the
 * word cloud)" instead of an empty chips row whenever the effective
 * Anthropic key is blank -- see [com.montauk.voicecapture.VoiceCaptureApp.isAnthropicKeyConfigured] --
 * so a user always understands *why* there are no tags, rather than being
 * shown a heuristic's fake-smart guess or a silently blank row.
 *
 * [TagScorerFactory.create] uses this both when no key is configured and as
 * [AnthropicTagScorer]'s own transient-failure fallback -- [HeuristicTagScorer]
 * is retired from the production path entirely, surviving only as a
 * deterministic test fixture where a test needs *some* scorer output.
 */
class NoOpTagScorer : TagScorer {
    override val minIntervalMs: Long = Long.MAX_VALUE

    override suspend fun score(transcriptTail: String, currentCandidates: List<String>): List<TagCandidate> = emptyList()
}
