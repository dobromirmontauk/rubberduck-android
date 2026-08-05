package com.montauk.voicecapture.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encoding must match the voice-vault ingest contract's `live-transcript.jsonl` schema exactly. */
class LiveTranscriptLineTest {

    @Test
    fun `encodeLine matches the ingest contract field names and shape`() {
        val line = LiveTranscriptLine(t0Ms = 1200, t1Ms = 2450, text = "so the thing I wanted to talk about", final = true)

        val encoded = LiveTranscriptWriter.encodeLine(line)

        assertEquals(
            """{"t0_ms":1200,"t1_ms":2450,"text":"so the thing I wanted to talk about","final":true}""",
            encoded,
        )
    }

    @Test
    fun `encodeLine output round-trips through a generic JSON parser`() {
        val line = LiveTranscriptLine(t0Ms = 0, t1Ms = 500, text = "hello", final = false)

        val json = Json.parseToJsonElement(LiveTranscriptWriter.encodeLine(line)).jsonObject

        assertEquals(0, json["t0_ms"]?.toString()?.toInt())
        assertEquals(500, json["t1_ms"]?.toString()?.toInt())
        assertEquals("\"hello\"", json["text"].toString())
        assertTrue(json["final"].toString() == "false")
    }

    @Test
    fun `encodeLine escapes text containing quotes and newlines and round-trips`() {
        val line = LiveTranscriptLine(t0Ms = 10, t1Ms = 20, text = "she said \"hi\"\nthen left", final = true)

        val encoded = LiveTranscriptWriter.encodeLine(line)
        // Must stay one JSON object on one physical line (the .jsonl contract) --
        // an embedded literal newline would break line-oriented parsers.
        assertTrue(encoded.lines().size == 1)

        val decoded = Json.decodeFromString(LiveTranscriptLine.serializer(), encoded)
        assertEquals(line, decoded)
    }
}
