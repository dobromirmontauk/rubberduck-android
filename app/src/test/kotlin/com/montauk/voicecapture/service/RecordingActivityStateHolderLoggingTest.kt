package com.montauk.voicecapture.service

import com.montauk.voicecapture.logging.LogSink
import com.montauk.voicecapture.logging.RubberduckLog
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead asn-jht: proves the "at least one state machine emitting on
 * transition" acceptance criterion against a real production state
 * machine ([RecordingActivityStateHolder]) rather than a synthetic example.
 */
class RecordingActivityStateHolderLoggingTest {

    @After
    fun tearDown() {
        RubberduckLog.overrideSinkForTest(null)
        RecordingActivityStateHolder.reset()
    }

    @Test
    fun `set logs a transition line when the state actually changes`() {
        val captured = mutableListOf<String>()
        RubberduckLog.overrideSinkForTest(object : LogSink {
            override fun append(line: String) {
                captured += line
            }
        })

        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING) // default state is QUIET

        assertTrue(
            captured.any {
                it.contains("component=RecordingActivityState") &&
                    it.contains("event=transition") &&
                    it.contains("from=QUIET") &&
                    it.contains("to=SPEAKING")
            },
        )
    }

    @Test
    fun `set logs nothing when the state doesn't actually change`() {
        RecordingActivityStateHolder.set(RecordingActivityState.QUIET) // already QUIET by default; primes the holder
        val captured = mutableListOf<String>()
        RubberduckLog.overrideSinkForTest(object : LogSink {
            override fun append(line: String) {
                captured += line
            }
        })

        RecordingActivityStateHolder.set(RecordingActivityState.QUIET)

        assertTrue(captured.isEmpty())
    }

    @Test
    fun `update logs the resulting transition too`() {
        val captured = mutableListOf<String>()
        RubberduckLog.overrideSinkForTest(object : LogSink {
            override fun append(line: String) {
                captured += line
            }
        })

        RecordingActivityStateHolder.update { RecordingActivityState.USER_PAUSED }

        assertTrue(
            captured.any {
                it.contains("component=RecordingActivityState") &&
                    it.contains("event=transition") &&
                    it.contains("to=USER_PAUSED")
            },
        )
    }
}
