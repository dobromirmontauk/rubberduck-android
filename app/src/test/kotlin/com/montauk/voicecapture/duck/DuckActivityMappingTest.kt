package com.montauk.voicecapture.duck

import com.montauk.voicecapture.service.RecordingActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class DuckActivityMappingTest {
    @Test
    fun `SPEAKING maps to LISTENING`() {
        assertEquals(DuckState.LISTENING, RecordingActivity.SPEAKING.toDuckState())
    }

    @Test
    fun `QUIET maps to SLEEPY`() {
        assertEquals(DuckState.SLEEPY, RecordingActivity.QUIET.toDuckState())
    }

    @Test
    fun `AUTO_PAUSED and USER_PAUSED both map to GONE_BRB`() {
        assertEquals(DuckState.GONE_BRB, RecordingActivity.AUTO_PAUSED.toDuckState())
        assertEquals(DuckState.GONE_BRB, RecordingActivity.USER_PAUSED.toDuckState())
    }
}
