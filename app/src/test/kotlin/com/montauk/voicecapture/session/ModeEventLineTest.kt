package com.montauk.voicecapture.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event-line shape must stay distinct from [LiveTranscriptLine] -- see that class's KDoc. */
class ModeEventLineTest {

    @Test
    fun `encodeLine matches the event-line shape exactly`() {
        val encoded = ModeEventWriter.encodeLine(ModeChange(1200L, RecordingMode.LISTEN))

        assertEquals("""{"t_ms":1200,"event":"mode","mode":"listen"}""", encoded)
    }

    @Test
    fun `encodeLine output round-trips through a generic JSON parser`() {
        val encoded = ModeEventWriter.encodeLine(ModeChange(0L, RecordingMode.LISTEN))

        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(0, json["t_ms"]?.toString()?.toInt())
        assertEquals("\"mode\"", json["event"].toString())
        assertEquals("\"listen\"", json["mode"].toString())
    }

    @Test
    fun `encodeLine stays on one physical line`() {
        val encoded = ModeEventWriter.encodeLine(ModeChange(42L, RecordingMode.LISTEN))

        assertTrue(encoded.lines().size == 1)
    }

    @Test
    fun `a mode event line fails to decode as a LiveTranscriptLine so readers skip it`() {
        val eventLine = ModeEventWriter.encodeLine(ModeChange(500L, RecordingMode.LISTEN))

        val decoded = runCatching { Json.decodeFromString(LiveTranscriptLine.serializer(), eventLine) }

        assertTrue("event lines must not be mistaken for transcript lines", decoded.isFailure)
    }
}
