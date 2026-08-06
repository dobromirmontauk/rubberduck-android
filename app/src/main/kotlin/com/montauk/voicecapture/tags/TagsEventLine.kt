package com.montauk.voicecapture.tags

import com.montauk.voicecapture.session.LiveTranscriptLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A `live-transcript.jsonl` event line for the current displayed-tags set,
 * appended every time that set changes: `{"t_ms": 1200, "event": "tags",
 * "tags": [{"tag": "kitchen-remodel", "tag_id": "t_01...", "confidence": 0.87, "rank": 1}]}`.
 * Bead vn-edu.47: `tag_id` is present (the vault's `tags.yaml` node id) only
 * when [com.montauk.voicecapture.tags.DisplayedTag.tagId] is non-null --
 * i.e. this tag was matched to an existing tree node, not a free-form
 * proposal or a legacy no-vault tag -- see [TagsEventWriter]'s `explicitNulls
 * = false` json config, which is what drops the key entirely rather than
 * writing `"tag_id":null`.
 *
 * Distinct in shape from [LiveTranscriptLine] (transcript segments) and from
 * [com.montauk.voicecapture.session.ModeEventLine] (mode changes) -- same
 * contract-tolerant pattern as that class: a reader that only cares about
 * transcript text decodes as [LiveTranscriptLine] and simply skips any line
 * that doesn't match that shape (see
 * [com.montauk.voicecapture.session.SessionStore.readTranscriptLines]). An
 * offline organizer that *does* care about tags derives per-tag intervals by
 * scanning consecutive "tags" event lines, and can now consume `tag_id`
 * directly as a pre-anchored segment hint (a follow-up vault-side bead,
 * per vn-edu.47's acceptance criteria) instead of re-matching tag text
 * against `tags.yaml` itself.
 */
@Serializable
data class TagEventEntry(
    val tag: String,
    @SerialName("tag_id") val tagId: String? = null,
    val confidence: Double,
    val rank: Int,
)

@Serializable
data class TagsEventLine(
    @SerialName("t_ms") val tMs: Long,
    val event: String = "tags",
    val tags: List<TagEventEntry>,
)

object TagsEventWriter {
    // Compact (no pretty-print), matching ModeEventWriter/LiveTranscriptWriter:
    // one JSON object per physical line. encodeDefaults=true for the same
    // reason as ModeEventWriter -- "event" always carries "tags", which is
    // exactly the default-valued field kotlinx.serialization would otherwise
    // omit. explicitNulls=false (bead vn-edu.47) drops TagEventEntry.tagId's
    // key entirely when null, rather than writing "tag_id":null, so a
    // free-form (non-tree-matched) tag's wire shape is byte-identical to
    // before this bead -- see TagsEventLineTest's exact-string assertions.
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun encodeLine(tMs: Long, displayed: List<DisplayedTag>): String {
        val line = TagsEventLine(
            tMs = tMs,
            tags = displayed.map { TagEventEntry(tag = it.tag, tagId = it.tagId, confidence = it.confidence, rank = it.rank) },
        )
        return json.encodeToString(TagsEventLine.serializer(), line)
    }
}
