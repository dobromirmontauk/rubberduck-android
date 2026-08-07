package com.montauk.voicecapture.duck

import com.montauk.voicecapture.service.RecordingActivityState
import org.junit.Assert.assertEquals
import org.junit.Test

class DuckActivityMappingTest {
    @Test
    fun `SPEAKING maps to ATTENTIVE regardless of fill fraction`() {
        assertEquals(DuckState.ATTENTIVE, RecordingActivityState.SPEAKING.toDuckState(autoPauseFillFraction = 0f))
        assertEquals(DuckState.ATTENTIVE, RecordingActivityState.SPEAKING.toDuckState(autoPauseFillFraction = 0.6f))
    }

    // --- bead asn-3h6: fresh-session / low-quiet regression coverage ---

    @Test
    fun `QUIET with a zero fill fraction maps to ATTENTIVE, not SLEEP -- fresh-session regression`() {
        // This is exactly a fresh session's starting condition:
        // RecordingActivityStateHolder.reset() leaves QUIET, and
        // TranscriptUiState's default autoPauseFillFraction is 0f -- the
        // duck must render awake, not asleep, before any speech happens.
        assertEquals(DuckState.ATTENTIVE, RecordingActivityState.QUIET.toDuckState(autoPauseFillFraction = 0f))
    }

    @Test
    fun `QUIET with the default (unspecified) fill fraction argument also maps to ATTENTIVE`() {
        assertEquals(DuckState.ATTENTIVE, RecordingActivityState.QUIET.toDuckState())
    }

    @Test
    fun `QUIET with any positive fill fraction maps to DROWSY`() {
        assertEquals(DuckState.DROWSY, RecordingActivityState.QUIET.toDuckState(autoPauseFillFraction = 0.01f))
        assertEquals(DuckState.DROWSY, RecordingActivityState.QUIET.toDuckState(autoPauseFillFraction = 0.5f))
        assertEquals(DuckState.DROWSY, RecordingActivityState.QUIET.toDuckState(autoPauseFillFraction = 1f))
    }

    @Test
    fun `AUTO_PAUSED and USER_PAUSED both map to SLEEP regardless of fill fraction`() {
        assertEquals(DuckState.SLEEP, RecordingActivityState.AUTO_PAUSED.toDuckState(autoPauseFillFraction = 0f))
        assertEquals(DuckState.SLEEP, RecordingActivityState.AUTO_PAUSED.toDuckState(autoPauseFillFraction = 1f))
        assertEquals(DuckState.SLEEP, RecordingActivityState.USER_PAUSED.toDuckState(autoPauseFillFraction = 0f))
        assertEquals(DuckState.SLEEP, RecordingActivityState.USER_PAUSED.toDuckState(autoPauseFillFraction = 1f))
    }
}
