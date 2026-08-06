package com.montauk.voicecapture.duck

import com.montauk.voicecapture.service.RecordingActivityState
import org.junit.Assert.assertEquals
import org.junit.Test

class DuckActivityMappingTest {
    @Test
    fun `SPEAKING maps to LISTENING`() {
        assertEquals(DuckState.LISTENING, RecordingActivityState.SPEAKING.toDuckState())
    }

    @Test
    fun `QUIET maps to SLEEPY`() {
        assertEquals(DuckState.SLEEPY, RecordingActivityState.QUIET.toDuckState())
    }

    @Test
    fun `AUTO_PAUSED and USER_PAUSED both map to SLEEPING`() {
        assertEquals(DuckState.SLEEPING, RecordingActivityState.AUTO_PAUSED.toDuckState())
        assertEquals(DuckState.SLEEPING, RecordingActivityState.USER_PAUSED.toDuckState())
    }
}
