package com.montauk.voicecapture.tags

import com.montauk.voicecapture.session.LiveTranscriptLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event-line shape must stay distinct from [LiveTranscriptLine] -- same regression shape as ModeEventLineTest. */
class TagsEventLineTest {

    private fun tag(tag: String, confidence: Double, rank: Int) = DisplayedTag(tag, confidence, rank, TagTier.forConfidence(confidence))

    @Test
    fun `encodeLine matches the event-line shape exactly`() {
        val encoded = TagsEventWriter.encodeLine(1200L, listOf(tag("marathon training", 0.87, 1)))

        assertEquals("""{"t_ms":1200,"event":"tags","tags":[{"tag":"marathon training","confidence":0.87,"rank":1}]}""", encoded)
    }

    @Test
    fun `encodeLine handles multiple tags in rank order`() {
        val encoded = TagsEventWriter.encodeLine(
            5_000L,
            listOf(tag("marathon training", 0.9, 1), tag("nutrition", 0.86, 2)),
        )

        val root = Json.parseToJsonElement(encoded).jsonObject
        val tags = root["tags"]!!.jsonArray
        assertEquals(2, tags.size)
        assertEquals("marathon training", tags[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals(1, tags[0].jsonObject["rank"]!!.jsonPrimitive.content.toInt())
        assertEquals("nutrition", tags[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals(2, tags[1].jsonObject["rank"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `encodeLine with an empty tags list still round-trips (all tags exited)`() {
        val encoded = TagsEventWriter.encodeLine(9_999L, emptyList())

        assertEquals("""{"t_ms":9999,"event":"tags","tags":[]}""", encoded)
    }

    @Test
    fun `encodeLine stays on one physical line`() {
        val encoded = TagsEventWriter.encodeLine(42L, listOf(tag("topic", 0.6, 1)))

        assertTrue(encoded.lines().size == 1)
    }

    @Test
    fun `a tags event line fails to decode as a LiveTranscriptLine so readers skip it`() {
        val eventLine = TagsEventWriter.encodeLine(500L, listOf(tag("topic", 0.6, 1)))

        val decoded = runCatching { Json.decodeFromString(LiveTranscriptLine.serializer(), eventLine) }

        assertTrue("event lines must not be mistaken for transcript lines", decoded.isFailure)
    }

    @Test
    fun `a tags event line is distinguishable from a mode event line by its event field`() {
        val encoded = TagsEventWriter.encodeLine(0L, listOf(tag("topic", 0.6, 1)))

        val event = Json.parseToJsonElement(encoded).jsonObject["event"]!!.jsonPrimitive.content
        assertEquals("tags", event)
    }
}
