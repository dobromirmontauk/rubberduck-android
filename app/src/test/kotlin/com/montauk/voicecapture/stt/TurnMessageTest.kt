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
    fun `an in-progress Turn splits stableText from unstableTail on word-level finality`() {
        // Bead vn-edu.45: mid-turn message where the leading words have
        // already been marked word_is_final by AssemblyAI (immutable, won't
        // be revised) while the trailing ones are still forming -- the exact
        // shape that lets the UI paint the stable prefix solid without
        // waiting for end_of_turn.
        val raw = """
            {
              "type": "Turn",
              "turn_order": 5,
              "end_of_turn": false,
              "transcript": "so the marathon training is going well but",
              "words": [
                {"text": "so", "start": 0, "end": 150, "word_is_final": true},
                {"text": "the", "start": 150, "end": 250, "word_is_final": true},
                {"text": "marathon", "start": 250, "end": 600, "word_is_final": true},
                {"text": "training", "start": 600, "end": 950, "word_is_final": false},
                {"text": "is", "start": 950, "end": 1050, "word_is_final": false},
                {"text": "going", "start": 1050, "end": 1300, "word_is_final": false}
              ]
            }
        """.trimIndent()

        val partial = parseTurnMessage(raw)

        assertEquals("so the marathon", partial?.stableText)
        assertEquals("training is going", partial?.unstableTail)
        assertFalse(partial!!.isFinal)
    }

    @Test
    fun `an in-progress Turn with no word_is_final data yet has a blank stableText`() {
        val raw = """
            {"type": "Turn", "turn_order": 3, "end_of_turn": false, "transcript": "so the thing",
             "words": [{"text": "so", "start": 100, "end": 250}]}
        """.trimIndent()

        val partial = parseTurnMessage(raw)

        assertEquals("", partial?.stableText)
        assertEquals("so", partial?.unstableTail)
    }

    @Test
    fun `a final Turn message reports the whole line as stable via its own isFinal, not stableText`() {
        // Finalized turns render entirely solid via TranscriptRow.Final
        // already (see RecordingScreen) -- stableText/unstableTail only
        // matter for the still-open-turn rendering path, so a fully-final
        // message legitimately reports every word as stable.
        val raw = """
            {
              "type": "Turn",
              "turn_order": 0,
              "end_of_turn": true,
              "transcript": "Hello world.",
              "words": [
                {"text": "Hello", "start": 0, "end": 400, "word_is_final": true},
                {"text": "world.", "start": 420, "end": 900, "word_is_final": true}
              ]
            }
        """.trimIndent()

        val partial = parseTurnMessage(raw)

        assertTrue(partial?.isFinal == true)
        assertEquals("Hello world.", partial?.stableText)
        assertEquals("", partial?.unstableTail)
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
