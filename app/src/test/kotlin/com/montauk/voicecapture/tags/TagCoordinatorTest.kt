package com.montauk.voicecapture.tags

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCoordinatorTest {

    private class StubScorer(
        override val minIntervalMs: Long,
        private val resultsQueue: MutableList<List<TagCandidate>>,
    ) : TagScorer {
        val calls = mutableListOf<Pair<String, List<String>>>()
        var throwOnNextCall = false

        override suspend fun score(transcriptTail: String, currentCandidates: List<String>): List<TagCandidate> {
            calls += transcriptTail to currentCandidates
            if (throwOnNextCall) {
                throwOnNextCall = false
                throw RuntimeException("boom")
            }
            return if (resultsQueue.isNotEmpty()) resultsQueue.removeAt(0) else emptyList()
        }
    }

    @Test
    fun `first final line always scores immediately regardless of minIntervalMs`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 30_000L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val coordinator = TagCoordinator(scorer)

        val result = coordinator.onFinalLine("hello world", endMs = 0L)

        assertEquals(listOf("topic"), result?.map { it.tag })
        assertEquals(1, scorer.calls.size)
    }

    @Test
    fun `a second final line before minIntervalMs elapses does not re-score`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 30_000L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val coordinator = TagCoordinator(scorer)
        coordinator.onFinalLine("first line", endMs = 0L)

        val result = coordinator.onFinalLine("second line", endMs = 5_000L)

        assertNull(result)
        assertEquals(1, scorer.calls.size)
    }

    @Test
    fun `a final line after minIntervalMs elapses re-scores`() = runBlocking {
        val scorer = StubScorer(
            minIntervalMs = 10_000L,
            resultsQueue = mutableListOf(
                listOf(TagCandidate("topic", 0.9)),
                listOf(TagCandidate("topic", 0.9), TagCandidate("second topic", 0.85)),
            ),
        )
        val coordinator = TagCoordinator(scorer)
        coordinator.onFinalLine("first line", endMs = 0L)

        val result = coordinator.onFinalLine("second line", endMs = 11_000L)

        assertEquals(listOf("topic", "second topic"), result?.map { it.tag })
        assertEquals(2, scorer.calls.size)
    }

    @Test
    fun `returns null when the scorer ran but the displayed set is unchanged`() = runBlocking {
        val scorer = StubScorer(
            minIntervalMs = 0L,
            resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9)), listOf(TagCandidate("topic", 0.9))),
        )
        val coordinator = TagCoordinator(scorer)
        coordinator.onFinalLine("first line", endMs = 0L)

        val result = coordinator.onFinalLine("second line", endMs = 1_000L)

        assertNull(result)
    }

    @Test
    fun `rolling tail window drops lines older than the window from the text sent to the scorer`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf(emptyList(), emptyList()))
        val coordinator = TagCoordinator(scorer, rollingTailWindowMs = 10_000L)
        coordinator.onFinalLine("old line", endMs = 0L)

        coordinator.onFinalLine("new line", endMs = 20_000L)

        val secondCallTail = scorer.calls[1].first
        assertTrue("expected the stale line dropped from the tail, got: $secondCallTail", !secondCallTail.contains("old line"))
        assertTrue(secondCallTail.contains("new line"))
    }

    @Test
    fun `currentCandidates passed to the scorer reflect the tracker's current displayed tags`() = runBlocking {
        val scorer = StubScorer(
            minIntervalMs = 0L,
            resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9)), listOf(TagCandidate("topic", 0.9))),
        )
        val coordinator = TagCoordinator(scorer)
        coordinator.onFinalLine("first line", endMs = 0L)

        coordinator.onFinalLine("second line", endMs = 1_000L)

        assertEquals(listOf("topic"), scorer.calls[1].second)
    }

    @Test
    fun `a scorer that throws degrades to no candidates for that call, never propagates`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf())
        scorer.throwOnNextCall = true
        val coordinator = TagCoordinator(scorer)

        val result = coordinator.onFinalLine("first line", endMs = 0L)

        assertNull(result) // no candidates in, nothing displayed, no change
    }

    @Test
    fun `blank final lines contribute no text and never invoke the scorer on their own`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val coordinator = TagCoordinator(scorer)

        coordinator.onFinalLine("", endMs = 0L)

        assertTrue(scorer.calls.isEmpty())
    }

    @Test
    fun `currentDisplayed reflects the tracker without needing a new scorer call`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val coordinator = TagCoordinator(scorer)
        coordinator.onFinalLine("first line", endMs = 0L)

        assertEquals(listOf("topic"), coordinator.currentDisplayed().map { it.tag })
    }

    @Test
    fun `tick returns null when nothing about the displayed set changed`() {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf())
        val coordinator = TagCoordinator(scorer)

        assertNull(coordinator.tick(0L))
    }

    @Test
    fun `tick can surface a decay-driven exit without any new final line`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val tracker = TagTracker(decayHalfLifeMs = 5_000L, minDwellMs = 0L, exitThreshold = 0.3)
        val coordinator = TagCoordinator(scorer, tracker)
        coordinator.onFinalLine("first line", endMs = 0L)

        // ~6 half-lives later the tag should have decayed under the exit bar.
        val result = coordinator.tick(30_000L)

        assertTrue("expected the tag to have decayed out via tick(), got $result", result != null && result.isEmpty())
    }
}
