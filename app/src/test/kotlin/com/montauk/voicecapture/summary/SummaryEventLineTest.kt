package com.montauk.voicecapture.summary

import com.montauk.voicecapture.session.LiveTranscriptLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event-line shape must stay distinct from [LiveTranscriptLine] -- same regression shape as TagsEventLineTest/ModeEventLineTest. */
class SummaryEventLineTest {

    @Test
    fun `encodeLine matches the event-line shape exactly`() {
        val encoded = SummaryEventWriter.encodeLine(60_000L, listOf("Discussed moving the kickoff meeting to Tuesday."))

        assertEquals(
            """{"t_ms":60000,"event":"summary","added":["Discussed moving the kickoff meeting to Tuesday."]}""",
            encoded,
        )
    }

    @Test
    fun `encodeLine with no new bullets this round still round-trips (a stale-only round)`() {
        val encoded = SummaryEventWriter.encodeLine(120_000L, emptyList())

        assertEquals("""{"t_ms":120000,"event":"summary","added":[]}""", encoded)
    }

    @Test
    fun `encodeLine handles multiple added bullets in order`() {
        val encoded = SummaryEventWriter.encodeLine(60_000L, listOf("first new bullet", "second new bullet"))

        val root = Json.parseToJsonElement(encoded).jsonObject
        val added = root["added"]!!.jsonArray
        assertEquals(2, added.size)
        assertEquals("first new bullet", added[0].jsonPrimitive.content)
        assertEquals("second new bullet", added[1].jsonPrimitive.content)
    }

    @Test
    fun `encodeLine stays on one physical line`() {
        val encoded = SummaryEventWriter.encodeLine(42L, listOf("a bullet"))

        assertTrue(encoded.lines().size == 1)
    }

    @Test
    fun `a summary event line fails to decode as a LiveTranscriptLine so readers skip it`() {
        val eventLine = SummaryEventWriter.encodeLine(500L, listOf("a bullet"))

        val decoded = runCatching { Json.decodeFromString(LiveTranscriptLine.serializer(), eventLine) }

        assertTrue("event lines must not be mistaken for transcript lines", decoded.isFailure)
    }

    @Test
    fun `a summary event line is distinguishable from a tags or mode event line by its event field`() {
        val encoded = SummaryEventWriter.encodeLine(0L, listOf("a bullet"))

        val event = Json.parseToJsonElement(encoded).jsonObject["event"]!!.jsonPrimitive.content
        assertEquals("summary", event)
    }
}
