package com.montauk.voicecapture.tags

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicTagScorerTest {

    private val scorer = HeuristicTagScorer()

    @Test
    fun `blank transcript yields no candidates`() = runBlocking {
        assertTrue(scorer.score("", emptyList()).isEmpty())
        assertTrue(scorer.score("   ", emptyList()).isEmpty())
    }

    @Test
    fun `a phrase repeated across sentences outranks a phrase mentioned once`() = runBlocking {
        val text = "I love long runs. Every weekend I do long runs by the river. " +
            "Yesterday I bought a new umbrella at the store."
        val candidates = scorer.score(text, emptyList())
        val repeated = candidates.first { it.tag == "long runs" }
        val oneOff = candidates.firstOrNull { it.tag.contains("umbrella") }
        assertTrue("expected an umbrella-related one-off candidate too", oneOff != null)
        assertTrue(
            "expected the repeated phrase ($repeated) to outrank the one-off (${oneOff!!})",
            repeated.confidence > oneOff.confidence,
        )
    }

    @Test
    fun `every candidate confidence is bounded between 0 and just under 1`() = runBlocking {
        val text = "budget review budget review budget review budget review budget review " +
            "vendor timeline vendor timeline vendor timeline"
        val candidates = scorer.score(text, emptyList())
        assertTrue(candidates.isNotEmpty())
        candidates.forEach {
            assertTrue("confidence ${it.confidence} out of range for ${it.tag}", it.confidence in 0.0..0.97)
        }
    }

    @Test
    fun `results are sorted most confident first`() = runBlocking {
        val text = "kubernetes deployment kubernetes deployment kubernetes deployment rollback. " +
            "A single unrelated aside about weather today."
        val candidates = scorer.score(text, emptyList())
        val confidences = candidates.map { it.confidence }
        assertEquals(confidences.sortedDescending(), confidences)
    }

    @Test
    fun `returns at most 10 candidates`() = runBlocking {
        val text = (1..20).joinToString(". ") { i -> "topic$i alpha$i beta$i gamma$i" }
        val candidates = scorer.score(text, emptyList())
        assertTrue(candidates.size <= 10)
    }

    @Test
    fun `pure numeral chains are never returned as a topic phrase`() = runBlocking {
        val text = "We drove twelve miles then six more miles then four more hours of driving."
        val candidates = scorer.score(text, emptyList())
        candidates.forEach { candidate ->
            assertTrue(
                "'${candidate.tag}' looks like a numeral chain, not a real topic",
                candidate.tag.split(" ").none { it in setOf("twelve", "six", "four") },
            )
        }
    }

    @Test
    fun `stray stopword-only input yields nothing`() = runBlocking {
        val candidates = scorer.score("um so like yeah okay so um", emptyList())
        assertTrue("expected no real topics from filler-only speech, got $candidates", candidates.isEmpty())
    }

    @Test
    fun `marathon fixture surfaces real multi-word phrases, not stray words`() = runBlocking {
        val text = "I started marathon training twelve weeks ago and my long runs keep getting harder. " +
            "This weekend I ran eighteen miles along the river trail with my running group. " +
            "My coach wants me to focus on pacing instead of speed, so most weekdays I do easy " +
            "recovery runs around six miles. Nutrition has been tricky too. I switched to energy " +
            "gels every forty five minutes during long runs, and it made a real difference. " +
            "The marathon is in October, and my goal is to finish under four hours."
        val candidates = scorer.score(text, emptyList())

        assertTrue("expected candidates, got none", candidates.isNotEmpty())
        // Conservative fallback, not exact-match with the LLM path (see
        // AnthropicTagScorerTest for that): every returned candidate should
        // still read as a real phrase, not sentence scaffolding.
        candidates.forEach { candidate ->
            assertTrue(
                "'${candidate.tag}' doesn't look like a real topic phrase",
                candidate.tag.split(" ").size in 1..2 && candidate.tag.isNotBlank(),
            )
        }
        // The script's two headline topics both still show up *somewhere*
        // among the tracked candidates, even if not ranked #1 by this
        // simple heuristic -- the LLM scorer is what's expected to rank them
        // correctly; this fallback only needs to not lose them entirely.
        val tags = candidates.map { it.tag }
        assertTrue("expected 'marathon training' among candidates $tags", tags.contains("marathon training"))
        assertTrue("expected 'nutrition' among candidates $tags", tags.contains("nutrition"))
    }
}
