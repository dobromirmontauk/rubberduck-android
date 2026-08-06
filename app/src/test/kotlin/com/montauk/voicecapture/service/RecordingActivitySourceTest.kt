package com.montauk.voicecapture.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingActivitySourceTest {

    @Test
    fun `silence hint visible means QUIET`() {
        val transcript = TranscriptUiState(silenceHintVisible = true)
        assertEquals(RecordingActivity.QUIET, crudeRecordingActivity(transcript))
    }

    @Test
    fun `no silence hint means SPEAKING`() {
        val transcript = TranscriptUiState(currentPartial = "and then we", silenceHintVisible = false)
        assertEquals(RecordingActivity.SPEAKING, crudeRecordingActivity(transcript))
    }

    @Test
    fun `fresh session with neither signal defaults to SPEAKING`() {
        assertEquals(RecordingActivity.SPEAKING, crudeRecordingActivity(TranscriptUiState()))
    }
}
