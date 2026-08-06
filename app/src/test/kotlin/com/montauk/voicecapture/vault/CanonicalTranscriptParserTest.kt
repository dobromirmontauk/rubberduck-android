package com.montauk.voicecapture.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CanonicalTranscriptParser]'s tolerant parsing of `transcript.json` (bead
 * vn-edu.57) -- pure JVM, no Robolectric.
 */
class CanonicalTranscriptParserTest {

    @Test
    fun `parses a well-formed transcript with multiple utterances`() {
        val raw = """
            {
              "words": [],
              "utterances": [
                {"t0_ms": 16, "t1_ms": 8296, "speaker": "A", "text": "Sitting in the lounge."},
                {"t0_ms": 8651, "t1_ms": 27769, "speaker": "A", "text": "This customer visit went better than expected."}
              ],
              "provider": "assemblyai",
              "model": "universal-3-5-pro",
              "transcribed_at": "2026-08-05T00:46:45-07:00"
            }
        """.trimIndent()

        val transcript = CanonicalTranscriptParser.parse(raw)

        assertEquals(2, transcript.utterances.size)
        assertEquals("A", transcript.utterances[0].speaker)
        assertEquals(16L, transcript.utterances[0].t0Ms)
        assertEquals(8296L, transcript.utterances[0].t1Ms)
        assertEquals("Sitting in the lounge.", transcript.utterances[0].text)
    }

    @Test
    fun `null input resolves to an empty transcript`() {
        assertEquals(CanonicalTranscript.EMPTY, CanonicalTranscriptParser.parse(null))
    }

    @Test
    fun `blank input resolves to an empty transcript`() {
        assertEquals(CanonicalTranscript.EMPTY, CanonicalTranscriptParser.parse("   "))
    }

    @Test
    fun `malformed JSON resolves to an empty transcript rather than throwing`() {
        val transcript = CanonicalTranscriptParser.parse("{not valid json")

        assertEquals(CanonicalTranscript.EMPTY, transcript)
    }

    @Test
    fun `a missing utterances key resolves to an empty transcript`() {
        val raw = """{"words": [], "provider": "assemblyai"}"""

        val transcript = CanonicalTranscriptParser.parse(raw)

        assertTrue(transcript.utterances.isEmpty())
    }

    @Test
    fun `a null utterances value resolves to an empty transcript`() {
        val raw = """{"utterances": null}"""

        val transcript = CanonicalTranscriptParser.parse(raw)

        assertTrue(transcript.utterances.isEmpty())
    }

    @Test
    fun `an empty utterances array resolves to an empty transcript`() {
        val raw = """{"utterances": []}"""

        val transcript = CanonicalTranscriptParser.parse(raw)

        assertTrue(transcript.utterances.isEmpty())
    }

    @Test
    fun `an utterance missing text is dropped rather than rendered blank`() {
        val raw = """
            {
              "utterances": [
                {"t0_ms": 0, "t1_ms": 100, "speaker": "A"},
                {"t0_ms": 100, "t1_ms": 200, "speaker": "A", "text": "kept"}
              ]
            }
        """.trimIndent()

        val transcript = CanonicalTranscriptParser.parse(raw)

        assertEquals(1, transcript.utterances.size)
        assertEquals("kept", transcript.utterances[0].text)
    }

    @Test
    fun `an utterance missing speaker still parses, with speaker null`() {
        val raw = """{"utterances": [{"t0_ms": 0, "t1_ms": 100, "text": "no speaker label"}]}"""

        val transcript = CanonicalTranscriptParser.parse(raw)

        assertEquals(1, transcript.utterances.size)
        assertEquals(null, transcript.utterances[0].speaker)
    }

    @Test
    fun `unknown top-level and per-utterance keys are ignored`() {
        val raw = """
            {
              "utterances": [{"t0_ms": 0, "t1_ms": 100, "speaker": "A", "text": "hi", "confidence": 0.9}],
              "some_future_field": 42
            }
        """.trimIndent()

        val transcript = CanonicalTranscriptParser.parse(raw)

        assertEquals(1, transcript.utterances.size)
        assertEquals("hi", transcript.utterances[0].text)
    }
}
