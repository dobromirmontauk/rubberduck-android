package com.montauk.voicecapture.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagTrackerTest {

    @Test
    fun `ranks displayed tags most confident first`() {
        val tracker = TagTracker()
        // All 3 confidences clear their respective slot's entry bar (slot 3's
        // is 0.88 -- see DEFAULT_ENTRY_THRESHOLDS) so ranking, not entry
        // eligibility, is what this test is exercising.
        val displayed = tracker.onScored(
            listOf(TagCandidate("alpha", 0.9), TagCandidate("bravo", 0.95), TagCandidate("charlie", 0.89)),
            nowMs = 0L,
        )
        assertEquals(listOf("bravo", "alpha", "charlie"), displayed.map { it.tag })
        assertEquals(listOf(1, 2, 3), displayed.map { it.rank })
    }

    @Test
    fun `a single candidate below the primary entry threshold is not displayed`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(listOf(TagCandidate("weak", 0.2)), nowMs = 0L)
        assertTrue("expected nothing displayed, got $displayed", displayed.isEmpty())
    }

    @Test
    fun `a strong primary tag is displayed alone when no other candidate clears slot 2's very high bar`() {
        val tracker = TagTracker()
        // 0.6 clears slot 1's entry bar (0.55) but not slot 2's (0.82) --
        // exactly the "very high bar for a 2nd/3rd tag" spec requirement.
        val displayed = tracker.onScored(
            listOf(TagCandidate("marathon training", 0.9), TagCandidate("weekend plans", 0.6)),
            nowMs = 0L,
        )
        assertEquals(listOf("marathon training"), displayed.map { it.tag })
    }

    @Test
    fun `a candidate clearing slot 2's very high bar is displayed alongside the primary tag`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(
            listOf(TagCandidate("marathon training", 0.9), TagCandidate("nutrition", 0.85)),
            nowMs = 0L,
        )
        assertEquals(listOf("marathon training", "nutrition"), displayed.map { it.tag })
    }

    @Test
    fun `min dwell keeps a displayed tag on screen even after its confidence craters`() {
        val tracker = TagTracker(minDwellMs = 10_000L, exitThreshold = 0.3)
        tracker.onScored(listOf(TagCandidate("topic", 0.9)), nowMs = 0L)

        // Confidence collapses well below the exit bar, but only 3s of the
        // 10s dwell window have elapsed -- must still be displayed.
        val displayed = tracker.onScored(listOf(TagCandidate("topic", 0.05)), nowMs = 3_000L)
        assertEquals(listOf("topic"), displayed.map { it.tag })
    }

    @Test
    fun `a tag exits once dwell has elapsed and confidence stays below the exit bar`() {
        val tracker = TagTracker(minDwellMs = 10_000L, exitThreshold = 0.3)
        tracker.onScored(listOf(TagCandidate("topic", 0.9)), nowMs = 0L)
        tracker.onScored(listOf(TagCandidate("topic", 0.05)), nowMs = 3_000L)

        // Dwell has now elapsed (11s > 10s) and confidence is still under 0.3.
        val displayed = tracker.onScored(listOf(TagCandidate("topic", 0.05)), nowMs = 11_000L)
        assertTrue("expected topic to have exited, got $displayed", displayed.isEmpty())
    }

    @Test
    fun `a displayed tag survives a low confidence dip if it clears the exit bar again before dwell elapses`() {
        val tracker = TagTracker(minDwellMs = 5_000L, exitThreshold = 0.3)
        tracker.onScored(listOf(TagCandidate("topic", 0.9)), nowMs = 0L)
        // Well past dwell, but still above the (much lower) exit bar.
        val displayed = tracker.onScored(listOf(TagCandidate("topic", 0.35)), nowMs = 20_000L)
        assertEquals(listOf("topic"), displayed.map { it.tag })
    }

    @Test
    fun `a stale candidate decays toward zero confidence when absent from later scorer calls`() {
        val tracker = TagTracker(decayHalfLifeMs = 10_000L, minDwellMs = 0L, exitThreshold = 0.3)
        tracker.onScored(listOf(TagCandidate("fading topic", 0.9)), nowMs = 0L)

        // Feed a different candidate at t=10_000 (one decay half-life later) --
        // "fading topic" is absent from this batch, so it should decay to ~0.45.
        tracker.onScored(listOf(TagCandidate("other", 0.9)), nowMs = 10_000L)
        // A third call, still not mentioning "fading topic", pushes it under
        // the exit bar and (dwell already elapsed) out of the display.
        val displayed = tracker.onScored(listOf(TagCandidate("other", 0.9)), nowMs = 30_000L)
        assertTrue(
            "expected 'fading topic' to have decayed out of the display, got $displayed",
            displayed.none { it.tag == "fading topic" },
        )
    }

    @Test
    fun `tick alone (no new scores) lets a displayed tag decay and exit over time`() {
        val tracker = TagTracker(decayHalfLifeMs = 5_000L, minDwellMs = 0L, exitThreshold = 0.3)
        tracker.onScored(listOf(TagCandidate("topic", 0.9)), nowMs = 0L)

        assertEquals(listOf("topic"), tracker.tick(1_000L).map { it.tag })
        // ~6 half-lives later, 0.9 * 0.5^6 ~= 0.014 -- comfortably under 0.3.
        val displayed = tracker.tick(30_000L)
        assertTrue("expected topic to have decayed out, got $displayed", displayed.isEmpty())
    }

    @Test
    fun `internally tracks at most maxCandidates regardless of how many distinct candidates are scored`() {
        val tracker = TagTracker(maxCandidates = 10)
        val many = (1..15).map { i -> TagCandidate("topic-$i", confidence = i / 20.0) }
        tracker.onScored(many, nowMs = 0L)
        assertTrue(
            "expected at most 10 tracked candidates, got ${tracker.trackedCandidateCount()}",
            tracker.trackedCandidateCount() <= 10,
        )
    }

    @Test
    fun `reordering keeps a currently-displayed tag's slot without re-clearing the entry bar`() {
        val tracker = TagTracker()
        // "second" clears slot 2's very high bar (0.85 >= 0.82) and gets in.
        tracker.onScored(listOf(TagCandidate("first", 0.9), TagCandidate("second", 0.85)), nowMs = 0L)
        // "second" drops to 0.6 -- below slot 2's entry bar, but it's already
        // displayed (sticky), so it must not be evicted just for having
        // fallen below the bar a *new* candidate would need to clear.
        val displayed = tracker.onScored(listOf(TagCandidate("first", 0.9), TagCandidate("second", 0.6)), nowMs = 1_000L)
        assertEquals(listOf("first", "second"), displayed.map { it.tag })
    }

    @Test
    fun `confidence is coerced into 0 to 1 even if a scorer misbehaves`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(listOf(TagCandidate("topic", 5.0)), nowMs = 0L)
        assertEquals(1.0, displayed.single().confidence, 0.0001)
    }

    @Test
    fun `blank tag text from a scorer is ignored rather than displayed`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(listOf(TagCandidate("   ", 0.99), TagCandidate("real topic", 0.9)), nowMs = 0L)
        assertEquals(listOf("real topic"), displayed.map { it.tag })
    }

    @Test
    fun `current returns the same set onScored last produced`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(listOf(TagCandidate("topic", 0.9)), nowMs = 0L)
        assertEquals(displayed, tracker.current())
    }

    @Test
    fun `no candidates ever means nothing is displayed and current is empty`() {
        val tracker = TagTracker()
        assertTrue(tracker.current().isEmpty())
        assertTrue(tracker.onScored(emptyList(), nowMs = 0L).isEmpty())
    }

    // --- Bead vn-edu.46: substring/high-overlap suppression -----------------

    @Test
    fun `an exact AC example -- candidate 0_7 and candidate might 0_72 -- displays only one`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(
            listOf(TagCandidate("candidate", 0.7), TagCandidate("candidate might", 0.72)),
            nowMs = 0L,
        )
        assertEquals("one topic, two phrasings, must collapse to one displayed chip", 1, displayed.size)
    }

    @Test
    fun `of an overlapping pair, the higher-confidence phrasing survives`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(
            listOf(TagCandidate("candidate", 0.7), TagCandidate("candidate might", 0.72)),
            nowMs = 0L,
        )
        assertEquals(listOf("candidate might"), displayed.map { it.tag })
    }

    @Test
    fun `word-subset overlap is suppressed even without an exact prefix match`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(
            listOf(TagCandidate("kitchen remodel", 0.9), TagCandidate("kitchen remodel budget", 0.8)),
            nowMs = 0L,
        )
        assertEquals(listOf("kitchen remodel"), displayed.map { it.tag })
    }

    @Test
    fun `genuinely different topics with no word overlap are never suppressed`() {
        val tracker = TagTracker()
        val displayed = tracker.onScored(
            listOf(TagCandidate("marathon training", 0.9), TagCandidate("nutrition", 0.85)),
            nowMs = 0L,
        )
        assertEquals(2, displayed.size)
    }

    @Test
    fun `a substring at the character level but not the word level is not suppressed`() {
        val tracker = TagTracker()
        // "cab" is a character-substring of "candidate" but shares no whole
        // word with it -- word-boundary-aware containment must not conflate
        // the two into one topic.
        val displayed = tracker.onScored(
            listOf(TagCandidate("cab fare", 0.9), TagCandidate("candidate debate", 0.85)),
            nowMs = 0L,
        )
        assertEquals(2, displayed.size)
    }

    @Test
    fun `suppression also applies across an existing tracked candidate and a new overlapping one`() {
        val tracker = TagTracker()
        tracker.onScored(listOf(TagCandidate("candidate", 0.7)), nowMs = 0L)

        // A later scorer call introduces the overlapping, higher-confidence phrasing.
        val displayed = tracker.onScored(listOf(TagCandidate("candidate might", 0.72)), nowMs = 1_000L)

        assertEquals(listOf("candidate might"), displayed.map { it.tag })
        assertEquals("the suppressed candidate must not linger in the tracked pool", 1, tracker.trackedCandidateCount())
    }

    // Note: suppression's interaction with an *already-displayed, still-
    // dwelling* tag being outscored same-tick by a fresh overlapping
    // candidate is deliberately not asserted here -- that's a genuinely
    // different, unspecified scenario from the bead's AC (which only
    // describes two candidates arriving together with nothing yet
    // displayed), and every pre-existing hysteresis test above (dwell,
    // sticky rank, exit threshold) passes unmodified with this feature
    // added -- none of them ever scores two overlapping candidate texts, so
    // suppression never activates for them.
}
