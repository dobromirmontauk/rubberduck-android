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

        assertEquals(
            """{"t_ms":1200,"event":"tags","tags":[{"tag":"marathon training","confidence":0.87,"rank":1,"status":"proposed_new","approved":false}]}""",
            encoded,
        )
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
            """{"t_ms":1200,"event":"tags","tags":[{"tag":"kitchen-remodel","tag_id":"t_01KZ8CKQ8Y60H8YQADG5RMNQ30","confidence":0.87,"rank":1,"status":"existing"}]}""",
            encoded,
        )
    }

    @Test
    fun `a free-form tag (null tagId) omits the tag_id key entirely, never writing a null-valued tag_id`() {
        val encoded = TagsEventWriter.encodeLine(1200L, listOf(tag("marathon training", 0.87, 1)))

        assertEquals(
            """{"t_ms":1200,"event":"tags","tags":[{"tag":"marathon training","confidence":0.87,"rank":1,"status":"proposed_new","approved":false}]}""",
            encoded,
        )
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

    // --- Bead asn-45m: user-attributed edit lines ---

    @Test
    fun `encodeUserEditLine matches the event-line shape exactly for a pure add`() {
        val encoded = TagsEventWriter.encodeUserEditLine(
            4200L,
            added = listOf(UserTagRef("kitchen-remodel", tagId = "t_01KZ8CKQ8Y60H8YQADG5RMNQ30")),
            removed = emptyList(),
        )

        assertEquals(
            """{"t_ms":4200,"event":"tags","source":"user","added":[{"tag":"kitchen-remodel","tag_id":"t_01KZ8CKQ8Y60H8YQADG5RMNQ30"}],"removed":[]}""",
            encoded,
        )
    }

    @Test
    fun `encodeUserEditLine matches the event-line shape exactly for a pure remove`() {
        val encoded = TagsEventWriter.encodeUserEditLine(4200L, added = emptyList(), removed = listOf(UserTagRef("gardening")))

        assertEquals("""{"t_ms":4200,"event":"tags","source":"user","added":[],"removed":[{"tag":"gardening"}]}""", encoded)
    }

    @Test
    fun `encodeUserEditLine for a swap carries both added and removed in one line`() {
        val encoded = TagsEventWriter.encodeUserEditLine(
            4200L,
            added = listOf(UserTagRef("landscaping", tagId = "t_landscape")),
            removed = listOf(UserTagRef("gardening", tagId = "t_garden")),
        )

        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("user", root["source"]!!.jsonPrimitive.content)
        assertEquals("landscaping", root["added"]!!.jsonArray[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("gardening", root["removed"]!!.jsonArray[0].jsonObject["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodeUserEditLine omits the tags key entirely -- the two variants never share a payload`() {
        val encoded = TagsEventWriter.encodeUserEditLine(0L, added = listOf(UserTagRef("topic")), removed = emptyList())

        assertTrue("a user-edit line must have no tags key", "\"tags\":" !in encoded)
    }

    @Test
    fun `encodeLine (model snapshot) omits source, added, and removed entirely -- unchanged from before asn-45m`() {
        val encoded = TagsEventWriter.encodeLine(1200L, listOf(tag("marathon training", 0.87, 1)))

        assertEquals(
            """{"t_ms":1200,"event":"tags","tags":[{"tag":"marathon training","confidence":0.87,"rank":1,"status":"proposed_new","approved":false}]}""",
            encoded,
        )
        assertTrue("source" !in encoded)
        assertTrue("added" !in encoded)
        assertTrue("removed" !in encoded)
    }

    // --- Bead asn-0jk: EXISTING/PROPOSED_NEW status + approved on new-tag events ---

    @Test
    fun `a tree-matched entry's status is existing and it never carries an approved key`() {
        val encoded = TagsEventWriter.encodeLine(0L, listOf(tag("kitchen-remodel", 0.9, 1, tagId = "t_kitchen")))

        val entry = Json.parseToJsonElement(encoded).jsonObject["tags"]!!.jsonArray[0].jsonObject
        assertEquals("existing", entry["status"]!!.jsonPrimitive.content)
        assertTrue("approved must not appear for an existing tag", "approved" !in entry)
    }

    @Test
    fun `a free-form entry's status is proposed_new and defaults approved to false when no approvedKeys are passed`() {
        val encoded = TagsEventWriter.encodeLine(0L, listOf(tag("gardening", 0.6, 1)))

        val entry = Json.parseToJsonElement(encoded).jsonObject["tags"]!!.jsonArray[0].jsonObject
        assertEquals("proposed_new", entry["status"]!!.jsonPrimitive.content)
        assertEquals(false, entry["approved"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a proposed_new entry reports approved true once its normalized tag is in approvedKeys`() {
        val encoded = TagsEventWriter.encodeLine(0L, listOf(tag("Gardening", 0.6, 1)), approvedKeys = setOf("gardening"))

        val entry = Json.parseToJsonElement(encoded).jsonObject["tags"]!!.jsonArray[0].jsonObject
        assertEquals("proposed_new", entry["status"]!!.jsonPrimitive.content)
        assertEquals(true, entry["approved"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `approvedKeys has no effect on an existing (tree-matched) entry`() {
        val encoded = TagsEventWriter.encodeLine(
            0L,
            listOf(tag("kitchen-remodel", 0.9, 1, tagId = "t_kitchen")),
            approvedKeys = setOf("kitchen-remodel"),
        )

        val entry = Json.parseToJsonElement(encoded).jsonObject["tags"]!!.jsonArray[0].jsonObject
        assertTrue("approved must still not appear for an existing tag even if its key is in approvedKeys", "approved" !in entry)
    }

    @Test
    fun `an approval user-edit line marks the added entry approved true, matching the encodeUserEditLine shape`() {
        val encoded = TagsEventWriter.encodeUserEditLine(
            4200L,
            added = listOf(UserTagRef("gardening", tagId = null, approved = true)),
            removed = emptyList(),
        )

        assertEquals(
            """{"t_ms":4200,"event":"tags","source":"user","added":[{"tag":"gardening","approved":true}],"removed":[]}""",
            encoded,
        )
    }

    @Test
    fun `an ordinary add (not an approval) omits the approved key on its UserTagRef`() {
        val encoded = TagsEventWriter.encodeUserEditLine(4200L, added = listOf(UserTagRef("kitchen-remodel", tagId = "t_kitchen")), removed = emptyList())

        assertTrue("approved must not appear for an ordinary add", "approved" !in encoded)
    }

    @Test
    fun `a user-edit line still decodes back into a TagsEventLine with tags null and source, added, removed populated`() {
        val encoded = TagsEventWriter.encodeUserEditLine(
            100L,
            added = listOf(UserTagRef("kitchen-remodel", tagId = "t_kitchen")),
            removed = listOf(UserTagRef("gardening")),
        )

        val decoded = Json.decodeFromString(TagsEventLine.serializer(), encoded)

        assertEquals("user", decoded.source)
        assertEquals(null, decoded.tags)
        assertEquals(listOf(UserTagRef("kitchen-remodel", "t_kitchen")), decoded.added)
        assertEquals(listOf(UserTagRef("gardening")), decoded.removed)
    }
}
