package com.montauk.voicecapture.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMessageTest {

    @Test
    fun `parses a final Turn message into an isFinal TranscriptPartial`() {
        val raw = """
            {
              "type": "Turn",
              "turn_order": 0,
              "turn_is_formatted": true,
              "end_of_turn": true,
              "transcript": "Hello world.",
              "utterance": "Hello world.",
              "end_of_turn_confidence": 1.0,
              "words": [
                {"text": "Hello", "start": 0, "end": 400, "confidence": 0.99, "word_is_final": true},
                {"text": "world.", "start": 420, "end": 900, "confidence": 0.97, "word_is_final": true}
              ]
            }
        """.trimIndent()

        val partial = parseTurnMessage(raw)

        assertEquals("Hello world.", partial?.text)
        assertTrue(partial?.isFinal == true)
        assertEquals(0L, partial?.startMs)
        assertEquals(900L, partial?.endMs)
    }

    @Test
    fun `parses an in-progress Turn message as not final`() {
        val raw = """
            {"type": "Turn", "turn_order": 3, "end_of_turn": false, "transcript": "so the thing",
             "words": [{"text": "so", "start": 100, "end": 250}]}
        """.trimIndent()

        val partial = parseTurnMessage(raw)

        assertEquals("so the thing", partial?.text)
        assertFalse(partial!!.isFinal)
    }

    @Test
    fun `a Turn with no words yet falls back to zero offsets`() {
        val raw = """{"type": "Turn", "turn_order": 0, "end_of_turn": false, "transcript": ""}"""

        val partial = parseTurnMessage(raw)

        assertEquals(0L, partial?.startMs)
        assertEquals(0L, partial?.endMs)
    }

    @Test
    fun `Begin and Termination messages are not transcript partials`() {
        assertNull(parseTurnMessage("""{"type": "Begin", "id": "abc", "expires_at": "2026-01-01T00:00:00Z"}"""))
        assertNull(parseTurnMessage("""{"type": "Termination", "audio_duration_seconds": 10, "session_duration_seconds": 12}"""))
    }

    @Test
    fun `malformed JSON does not throw, just yields null`() {
        assertNull(parseTurnMessage("not json at all"))
        assertNull(parseTurnMessage(""))
    }
}
