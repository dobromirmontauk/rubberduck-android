package com.montauk.voicecapture.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead vn-edu.56: the full duration x stt-connected x word-count rule
 * matrix for [TooShortPolicy.shouldDiscard]. The CRITICAL case
 * ([offlineLongSessionWithZeroTranscript_isNeverDiscarded]) is the one
 * GOAL.md's "audio is never lost" promise hinges on -- everything else is
 * secondary to that one never regressing.
 */
class TooShortPolicyTest {

    private val tenSeconds = TooShortPolicy.TOO_SHORT_SECONDS * 1_000L

    // --- duration-only trigger (a) -----------------------------------------

    @Test
    fun `3s accidental tap with no STT ever connected is discarded on duration alone`() {
        assertTrue(TooShortPolicy.shouldDiscard(durationMs = 3_000L, sttWasConnected = false, finalWordCount = 0))
    }

    @Test
    fun `duration exactly at the threshold is not discarded (strict less-than)`() {
        assertFalse(TooShortPolicy.shouldDiscard(durationMs = tenSeconds, sttWasConnected = false, finalWordCount = 0))
    }

    @Test
    fun `duration one ms under the threshold is discarded`() {
        assertTrue(TooShortPolicy.shouldDiscard(durationMs = tenSeconds - 1, sttWasConnected = false, finalWordCount = 0))
    }

    // --- offline immunity (the CRITICAL rule) -------------------------------

    @Test
    fun `offlineLongSessionWithZeroTranscript_isNeverDiscarded`() {
        // 15 minutes, STT never connected (keyless/offline build), zero transcript.
        val fifteenMinutesMs = 15 * 60 * 1_000L
        assertFalse(TooShortPolicy.shouldDiscard(durationMs = fifteenMinutesMs, sttWasConnected = false, finalWordCount = 0))
    }

    @Test
    fun `offline session with very few words is still never discarded on word count`() {
        val fifteenMinutesMs = 15 * 60 * 1_000L
        assertFalse(TooShortPolicy.shouldDiscard(durationMs = fifteenMinutesMs, sttWasConnected = false, finalWordCount = 1))
    }

    // --- STT-connected word-count trigger (b) -------------------------------

    @Test
    fun `30s session with STT connected but only 2 words is discarded`() {
        assertTrue(TooShortPolicy.shouldDiscard(durationMs = 30_000L, sttWasConnected = true, finalWordCount = 2))
    }

    @Test
    fun `word count exactly at the threshold is not discarded (strict less-than)`() {
        assertFalse(
            TooShortPolicy.shouldDiscard(durationMs = 30_000L, sttWasConnected = true, finalWordCount = TooShortPolicy.TOO_SHORT_WORDS),
        )
    }

    @Test
    fun `word count one below the threshold is discarded`() {
        assertTrue(
            TooShortPolicy.shouldDiscard(
                durationMs = 30_000L,
                sttWasConnected = true,
                finalWordCount = TooShortPolicy.TOO_SHORT_WORDS - 1,
            ),
        )
    }

    @Test
    fun `long session with STT connected and plenty of words is saved normally`() {
        assertFalse(TooShortPolicy.shouldDiscard(durationMs = 60_000L, sttWasConnected = true, finalWordCount = 42))
    }

    @Test
    fun `zero words with STT connected is discarded regardless of duration`() {
        assertTrue(TooShortPolicy.shouldDiscard(durationMs = 5 * 60 * 1_000L, sttWasConnected = true, finalWordCount = 0))
    }

    // --- combined matrix: duration long-enough, so only the STT/word-count arm decides ---

    @Test
    fun `long duration and stt never connected and zero words -- saved (offline path wins)`() {
        assertFalse(TooShortPolicy.shouldDiscard(durationMs = 20_000L, sttWasConnected = false, finalWordCount = 0))
    }

    @Test
    fun `long duration and stt connected and zero words -- discarded (word-count path wins)`() {
        assertTrue(TooShortPolicy.shouldDiscard(durationMs = 20_000L, sttWasConnected = true, finalWordCount = 0))
    }

    @Test
    fun `short duration always discards even with plenty of connected words`() {
        assertTrue(TooShortPolicy.shouldDiscard(durationMs = 2_000L, sttWasConnected = true, finalWordCount = 100))
    }
}
