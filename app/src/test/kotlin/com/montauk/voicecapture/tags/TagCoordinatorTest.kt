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
        val treesSeen = mutableListOf<TagTree>()
        var throwOnNextCall = false

        override suspend fun score(transcriptTail: String, currentCandidates: List<String>, tree: TagTree): List<TagCandidate> {
            calls += transcriptTail to currentCandidates
            treesSeen += tree
            if (throwOnNextCall) {
                throwOnNextCall = false
                throw RuntimeException("boom")
            }
            return if (resultsQueue.isNotEmpty()) resultsQueue.removeAt(0) else emptyList()
        }
    }

    /** Single-node fixture tree, just enough to prove a non-default treeProvider result actually reaches the scorer. */
    private fun fixtureTree(): TagTree = TagTree(listOf(TagTreeNode("t_home", "home", null, "House stuff.")))

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

    // --- Bead vn-edu.44: tags never appear when speech stays in one long partial ---
    //
    // User screenshots 2026-08-05: zero tag chips across a 47s continuously-
    // spoken session. TagCoordinator's rolling tail only ever fed on FINAL
    // lines (via onFinalLine); with no turn closed for a minute, the scorer
    // never received anything, so it never had a chance to produce a
    // candidate. Fix: onTick(nowMs, currentPartialText) folds the STT's
    // still-open partial into the rolling tail and lets the scorer's cadence
    // fire off recording-elapsed time instead of waiting on a closed turn.

    @Test
    fun `onTick feeds the scorer the current partial even with zero final lines`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val coordinator = TagCoordinator(scorer)

        val result = coordinator.onTick(nowMs = 1_000L, currentPartialText = "still talking about the same topic")

        assertEquals(listOf("still talking about the same topic"), scorer.calls.map { it.first })
        assertEquals(listOf("topic"), result?.map { it.tag })
    }

    @Test
    fun `onTick respects the scorer's cadence the same way onFinalLine does`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 30_000L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val coordinator = TagCoordinator(scorer)
        coordinator.onTick(nowMs = 0L, currentPartialText = "opening words")

        // Not re-scored yet (30s cadence, only 5s elapsed) -- a second call to
        // the scorer would be the bug here, not whether the returned displayed
        // set happens to be null: the decay-driven fallback path (same as the
        // older tick()) can legitimately report a non-null result on its own
        // as a tracked candidate's confidence keeps decaying between scorer
        // calls, independent of cadence.
        coordinator.onTick(nowMs = 5_000L, currentPartialText = "opening words plus a few more")

        assertEquals(1, scorer.calls.size)
    }

    @Test
    fun `onTick still surfaces decay-driven exits when the cadence is not due`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 999_000L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val tracker = TagTracker(decayHalfLifeMs = 5_000L, minDwellMs = 0L, exitThreshold = 0.3)
        val coordinator = TagCoordinator(scorer, tracker)
        coordinator.onTick(nowMs = 0L, currentPartialText = "the one and only topic")

        // Cadence isn't due again (minIntervalMs=999s), but decay/exit should still apply.
        val result = coordinator.onTick(nowMs = 30_000L, currentPartialText = "the one and only topic keeps going")

        assertTrue("expected the tag to have decayed out via onTick(), got $result", result != null && result.isEmpty())
    }

    /**
     * Failing-first repro (mandatory per bead vn-edu.44): a 60s single
     * continuously-growing partial, no final line ever closes, real
     * [HeuristicTagScorer] + real [TagTracker] (the "heuristic path", no
     * network/mocking) -- exactly the user's screenshot scenario. Simulates
     * the STT's replace-not-append partial semantics (each tick's
     * `currentPartialText` is the FULL transcript-so-far for the still-open
     * turn, matching [com.montauk.voicecapture.stt.TranscriptPartial.text]),
     * at a rough natural speaking pace (~2.5 words/sec / 150wpm), ticking
     * once per simulated second the same way [com.montauk.voicecapture.service.RecordingService]'s
     * ticker does.
     *
     * Also documents the "time to first tag" expectation from the bead's
     * acceptance criteria: a clearly single-topic monologue should surface a
     * first heuristic tag within ~40s of defaults.
     */
    @Test
    fun `a 60s single continuous partial with no closed turn still reaches the tracker via the heuristic path`() = runBlocking {
        // Modeled on the real drive-home-hiring.txt fixture's scenario
        // (app/src/debug/assets/fixtures/drive-home-hiring.txt) -- a single-
        // topic monologue ("this hiring decision") repeated/rephrased the way
        // real unscripted narration does, which is exactly the shape
        // HeuristicTagScorer's repetition + lead-position signals are tuned
        // to reward.
        val monologue = "I've been going back and forth all day on this hiring decision for the senior engineer role. " +
            "Candidate A is stronger technically, better system design skills, more relevant experience. " +
            "Candidate B is weaker technically but every interviewer loved candidate B's communication style. " +
            "This hiring decision really comes down to whether we need technical depth or team fit. " +
            "I keep coming back to this hiring decision every time I compare candidate A and candidate B. "
        val fullText = (monologue + monologue + monologue + monologue).trim()
        val words = fullText.split(" ")

        val coordinator = TagCoordinator(HeuristicTagScorer(), TagTracker())
        var firstTagAtMs: Long? = null

        for (second in 1..60) {
            val nowMs = second * 1_000L
            val wordCount = (second * WORDS_PER_SECOND).toInt().coerceAtMost(words.size)
            val partialSoFar = words.take(wordCount).joinToString(" ")
            coordinator.onTick(nowMs, partialSoFar)
            if (firstTagAtMs == null && coordinator.currentDisplayed().isNotEmpty()) {
                firstTagAtMs = nowMs
            }
        }

        assertTrue(
            "expected at least one candidate to reach the tracker via the heuristic path over a 60s " +
                "continuous partial with no closed turn -- got ${coordinator.currentDisplayed()}",
            coordinator.currentDisplayed().isNotEmpty(),
        )
        assertTrue("expected a first tag within the 60s simulated window", firstTagAtMs != null)
        assertTrue(
            "documented expectation (bead vn-edu.44 acceptance criteria): a clearly single-topic " +
                "monologue should surface a first heuristic tag within ~40s of defaults, got firstTagAtMs=$firstTagAtMs",
            firstTagAtMs!! <= 40_000L,
        )
    }

    // --- Bead vn-edu.47: treeProvider wiring ---

    @Test
    fun `with no treeProvider given, the scorer is called with TagTree EMPTY`() = runBlocking {
        val scorer = StubScorer(minIntervalMs = 0L, resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9))))
        val coordinator = TagCoordinator(scorer)

        coordinator.onFinalLine("first line", endMs = 0L)

        assertTrue(scorer.treesSeen.single().isEmpty)
    }

    @Test
    fun `a supplied treeProvider's tree is threaded through to every scorer call`() = runBlocking {
        val scorer = StubScorer(
            minIntervalMs = 0L,
            resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9)), listOf(TagCandidate("topic", 0.9))),
        )
        val tree = fixtureTree()
        val coordinator = TagCoordinator(scorer, treeProvider = { tree })
        coordinator.onFinalLine("first line", endMs = 0L)

        coordinator.onFinalLine("second line", endMs = 1_000L)

        assertEquals(listOf(tree, tree), scorer.treesSeen)
    }

    @Test
    fun `treeProvider is resolved at most once per coordinator, not once per scorer call`() = runBlocking {
        val scorer = StubScorer(
            minIntervalMs = 0L,
            resultsQueue = mutableListOf(listOf(TagCandidate("topic", 0.9)), listOf(TagCandidate("topic", 0.9))),
        )
        var provideCount = 0
        val coordinator = TagCoordinator(scorer, treeProvider = { provideCount++; fixtureTree() })
        coordinator.onFinalLine("first line", endMs = 0L)

        coordinator.onFinalLine("second line", endMs = 1_000L)

        assertEquals(1, provideCount)
    }

    @Test
    fun `a tree-matched candidate's tagId and isProposal survive through to the displayed tag`() = runBlocking {
        val scorer = StubScorer(
            minIntervalMs = 0L,
            resultsQueue = mutableListOf(listOf(TagCandidate("home", 0.9, tagId = "t_home", isProposal = false))),
        )
        val coordinator = TagCoordinator(scorer, treeProvider = { fixtureTree() })

        val result = coordinator.onFinalLine("first line", endMs = 0L)

        assertEquals("t_home", result?.single()?.tagId)
        assertEquals(false, result?.single()?.isProposal)
    }

    @Test
    fun `a proposal candidate's isProposal flag survives through to the displayed tag`() = runBlocking {
        val scorer = StubScorer(
            minIntervalMs = 0L,
            resultsQueue = mutableListOf(listOf(TagCandidate("gardening", 0.9, tagId = null, isProposal = true))),
        )
        val coordinator = TagCoordinator(scorer, treeProvider = { fixtureTree() })

        val result = coordinator.onFinalLine("first line", endMs = 0L)

        assertNull(result?.single()?.tagId)
        assertEquals(true, result?.single()?.isProposal)
    }

    private companion object {
        const val WORDS_PER_SECOND = 2.5
    }
}
