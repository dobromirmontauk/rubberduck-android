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
    fun `AUTO_PAUSED and USER_PAUSED both map to SLEEPING`() {
        assertEquals(DuckState.SLEEPING, RecordingActivity.AUTO_PAUSED.toDuckState())
        assertEquals(DuckState.SLEEPING, RecordingActivity.USER_PAUSED.toDuckState())
    }
}
