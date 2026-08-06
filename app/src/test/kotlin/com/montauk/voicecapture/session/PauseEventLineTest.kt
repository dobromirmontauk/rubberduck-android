package com.montauk.voicecapture.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event-line shape must stay distinct from [LiveTranscriptLine] -- see [PauseEventLine]'s KDoc. */
class PauseEventLineTest {

    @Test
    fun `encodeLine matches the exact user-pause wire shape from the bead`() {
        val encoded = PauseEventWriter.encodeLine(tMs = 1200L, event = "pause", reason = "user")

        assertEquals("""{"t_ms":1200,"event":"pause","reason":"user"}""", encoded)
    }

    @Test
    fun `encodeLine matches the exact auto-resume wire shape from the bead`() {
        val encoded = PauseEventWriter.encodeLine(tMs = 32_000L, event = "resume", reason = "auto")

        assertEquals("""{"t_ms":32000,"event":"resume","reason":"auto"}""", encoded)
    }

    @Test
    fun `encodeLine output round-trips through a generic JSON parser`() {
        val encoded = PauseEventWriter.encodeLine(tMs = 0L, event = "pause", reason = "auto")

        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(0, json["t_ms"]?.toString()?.toInt())
        assertEquals("\"pause\"", json["event"].toString())
        assertEquals("\"auto\"", json["reason"].toString())
    }

    @Test
    fun `encodeLine stays on one physical line`() {
        val encoded = PauseEventWriter.encodeLine(tMs = 42L, event = "resume", reason = "user")

        assertTrue(encoded.lines().size == 1)
    }

    @Test
    fun `a pause event line fails to decode as a LiveTranscriptLine so readers skip it`() {
        val eventLine = PauseEventWriter.encodeLine(tMs = 500L, event = "pause", reason = "user")

        val decoded = runCatching { Json.decodeFromString(LiveTranscriptLine.serializer(), eventLine) }

        assertTrue("event lines must not be mistaken for transcript lines", decoded.isFailure)
    }
}
