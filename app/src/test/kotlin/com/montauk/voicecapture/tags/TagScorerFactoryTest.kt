package com.montauk.voicecapture.tags

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead vn-edu.46 superseding decision (2026-08-05): keyless must compute NO
 * tags at all, never a heuristic guess. [TagScorerFactory.create] with a
 * blank key must hand back something that always returns an empty list --
 * these tests would have passed against the old `HeuristicTagScorer()`
 * fallback too (it also returns *some* list), so the meaningful assertion is
 * that a transcript containing an obvious repeated/lead-position phrase
 * -- exactly what [HeuristicTagScorer] is tuned to catch -- still yields
 * nothing from the keyless scorer.
 */
class TagScorerFactoryTest {

    @Test
    fun `blank key never produces tags, even for text a heuristic would catch`() = runBlocking {
        val scorer = TagScorerFactory.create("")

        // HeuristicTagScorerTest's own fixtures prove this exact kind of
        // repeated, lead-position phrase scores highly under that scorer --
        // the keyless factory output must not, confirming it's not secretly
        // still HeuristicTagScorer under a new name.
        val tail = "Marathon training update. Marathon training has been going well. Marathon training is the whole focus this month."

        val result = scorer.score(tail, emptyList())

        assertTrue("keyless scorer must never compute tags", result.isEmpty())
    }

    @Test
    fun `blank key scorer is not the retired HeuristicTagScorer instance type`() {
        val scorer = TagScorerFactory.create("")

        assertTrue(scorer !is HeuristicTagScorer)
    }

    @Test
    fun `configured key produces a real AnthropicTagScorer`() {
        val scorer = TagScorerFactory.create("sk-ant-configured")

        assertTrue(scorer is AnthropicTagScorer)
    }
}
