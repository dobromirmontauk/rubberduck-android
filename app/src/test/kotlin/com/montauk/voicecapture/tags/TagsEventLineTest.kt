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

    private fun tag(tag: String, confidence: Double, rank: Int, tagId: String? = null) =
        DisplayedTag(tag, confidence, rank, TagTier.forConfidence(confidence), tagId = tagId)

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

    // --- Bead vn-edu.47: tag_id on the wire ---

    @Test
    fun `a tree-matched tag (non-null tagId) carries a tag_id key in the encoded event`() {
        val encoded = TagsEventWriter.encodeLine(1200L, listOf(tag("kitchen-remodel", 0.87, 1, tagId = "t_01KZ8CKQ8Y60H8YQADG5RMNQ30")))

        assertEquals(
            """{"t_ms":1200,"event":"tags","tags":[{"tag":"kitchen-remodel","tag_id":"t_01KZ8CKQ8Y60H8YQADG5RMNQ30","confidence":0.87,"rank":1}]}""",
            encoded,
        )
    }

    @Test
    fun `a free-form tag (null tagId) omits the tag_id key entirely, never writing a null-valued tag_id`() {
        val encoded = TagsEventWriter.encodeLine(1200L, listOf(tag("marathon training", 0.87, 1)))

        assertEquals("""{"t_ms":1200,"event":"tags","tags":[{"tag":"marathon training","confidence":0.87,"rank":1}]}""", encoded)
        assertTrue("tag_id must not appear at all for a free-form tag", !encoded.contains("tag_id"))
    }

    @Test
    fun `a mix of tree-matched and free-form tags each carry the correct tag_id presence independently`() {
        val encoded = TagsEventWriter.encodeLine(
            0L,
            listOf(tag("kitchen-remodel", 0.9, 1, tagId = "t_kitchen"), tag("gardening", 0.6, 2)),
        )

        val tagsArray = Json.parseToJsonElement(encoded).jsonObject["tags"]!!.jsonArray
        assertEquals("t_kitchen", tagsArray[0].jsonObject["tag_id"]!!.jsonPrimitive.content)
        assertTrue("the second (free-form) entry must have no tag_id key", "tag_id" !in tagsArray[1].jsonObject)
    }
}
