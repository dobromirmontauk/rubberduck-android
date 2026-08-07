package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewTagEntranceTimelineTest {

    private val text = "kitchen-remodel" // 15 chars

    // --- WRITE ---

    @Test
    fun `at elapsed 0 is WRITE with zero letters revealed`() {
        val sample = NewTagEntranceTimeline.at(0L, text, reducedMotion = false)

        assertEquals(NewTagEntrancePhase.WRITE, sample.phase)
        assertEquals(0, sample.lettersRevealed)
        assertEquals(0f, sample.phaseProgress, 0.001f)
    }

    @Test
    fun `WRITE reveals letters proportionally to elapsed time`() {
        val halfway = NewTagEntranceTimeline.at(NewTagEntranceTimeline.WRITE_MS / 2, text, reducedMotion = false)

        assertEquals(NewTagEntrancePhase.WRITE, halfway.phase)
        assertEquals(0.5f, halfway.phaseProgress, 0.01f)
        assertEquals(text.length / 2, halfway.lettersRevealed)
    }

    @Test
    fun `WRITE has revealed nearly every letter by the time it hands off to MORPH`() {
        val justBeforeMorph = NewTagEntranceTimeline.at(NewTagEntranceTimeline.WRITE_MS - 1, text, reducedMotion = false)

        assertEquals(NewTagEntrancePhase.WRITE, justBeforeMorph.phase)
        assertTrue(justBeforeMorph.lettersRevealed >= text.length - 1)
    }

    // --- MORPH ---

    @Test
    fun `MORPH begins exactly at WRITE_MS with full text already revealed`() {
        val sample = NewTagEntranceTimeline.at(NewTagEntranceTimeline.WRITE_MS, text, reducedMotion = false)

        assertEquals(NewTagEntrancePhase.MORPH, sample.phase)
        assertEquals(0f, sample.phaseProgress, 0.001f)
        assertEquals(text.length, sample.lettersRevealed)
    }

    @Test
    fun `MORPH progress is relative to its own start, not the whole timeline`() {
        val midMorph = NewTagEntranceTimeline.at(
            NewTagEntranceTimeline.WRITE_MS + NewTagEntranceTimeline.MORPH_MS / 2,
            text,
            reducedMotion = false,
        )

        assertEquals(NewTagEntrancePhase.MORPH, midMorph.phase)
        assertEquals(0.5f, midMorph.phaseProgress, 0.01f)
    }

    // --- DRIFT ---

    @Test
    fun `DRIFT begins exactly at WRITE_MS plus MORPH_MS`() {
        val sample = NewTagEntranceTimeline.at(
            NewTagEntranceTimeline.WRITE_MS + NewTagEntranceTimeline.MORPH_MS,
            text,
            reducedMotion = false,
        )

        assertEquals(NewTagEntrancePhase.DRIFT, sample.phase)
        assertEquals(0f, sample.phaseProgress, 0.001f)
    }

    @Test
    fun `DRIFT progress is relative to its own start`() {
        val midDrift = NewTagEntranceTimeline.at(
            NewTagEntranceTimeline.WRITE_MS + NewTagEntranceTimeline.MORPH_MS + NewTagEntranceTimeline.DRIFT_MS / 2,
            text,
            reducedMotion = false,
        )

        assertEquals(NewTagEntrancePhase.DRIFT, midDrift.phase)
        assertEquals(0.5f, midDrift.phaseProgress, 0.01f)
    }

    // --- SETTLED ---

    @Test
    fun `SETTLED begins exactly at TOTAL_MS and stays settled forever after`() {
        assertEquals(
            NewTagEntranceTimeline.WRITE_MS + NewTagEntranceTimeline.MORPH_MS + NewTagEntranceTimeline.DRIFT_MS,
            NewTagEntranceTimeline.TOTAL_MS,
        )

        val atTotal = NewTagEntranceTimeline.at(NewTagEntranceTimeline.TOTAL_MS, text, reducedMotion = false)
        val wayLater = NewTagEntranceTimeline.at(NewTagEntranceTimeline.TOTAL_MS * 1000, text, reducedMotion = false)

        assertEquals(NewTagEntrancePhase.SETTLED, atTotal.phase)
        assertEquals(text.length, atTotal.lettersRevealed)
        assertEquals(NewTagEntrancePhase.SETTLED, wayLater.phase)
        assertEquals(1f, wayLater.phaseProgress, 0.001f)
    }

    // --- Storyboard timings (bead asn-bmq: "WRITE ~2.5s ... MORPH ~0.8s ... DRIFT ~1.5s") ---

    @Test
    fun `phase durations match the storyboard's timings`() {
        assertEquals(2500L, NewTagEntranceTimeline.WRITE_MS)
        assertEquals(800L, NewTagEntranceTimeline.MORPH_MS)
        assertEquals(1500L, NewTagEntranceTimeline.DRIFT_MS)
        assertEquals(4800L, NewTagEntranceTimeline.TOTAL_MS)
    }

    // --- Reduced motion (bead asn-bmq: "Reduced-motion setting shows a simple fade-in instead") ---

    @Test
    fun `reduced motion never enters WRITE or DRIFT -- only a short fade`() {
        val everySampledMs = (0..NewTagEntranceTimeline.TOTAL_MS step 50).map {
            NewTagEntranceTimeline.at(it, text, reducedMotion = true)
        }

        assertTrue(
            "reduced motion must never show the letter-by-letter WRITE phase or the translate+scale DRIFT phase",
            everySampledMs.none { it.phase == NewTagEntrancePhase.WRITE || it.phase == NewTagEntrancePhase.DRIFT },
        )
    }

    @Test
    fun `reduced motion reveals the full word immediately -- no letter-stepped reveal`() {
        val atStart = NewTagEntranceTimeline.at(0L, text, reducedMotion = true)

        assertEquals(text.length, atStart.lettersRevealed)
    }

    @Test
    fun `reduced motion settles in well under one second`() {
        assertTrue(NewTagEntranceTimeline.REDUCED_MOTION_FADE_MS < 1000L)

        val settled = NewTagEntranceTimeline.at(NewTagEntranceTimeline.REDUCED_MOTION_FADE_MS, text, reducedMotion = true)
        assertEquals(NewTagEntrancePhase.SETTLED, settled.phase)
    }

    @Test
    fun `reduced motion fades in (progress climbs monotonically from 0 to 1) rather than snapping`() {
        val early = NewTagEntranceTimeline.at(0L, text, reducedMotion = true)
        val mid = NewTagEntranceTimeline.at(NewTagEntranceTimeline.REDUCED_MOTION_FADE_MS / 2, text, reducedMotion = true)

        assertEquals(0f, early.phaseProgress, 0.001f)
        assertEquals(0.5f, mid.phaseProgress, 0.01f)
    }
}
