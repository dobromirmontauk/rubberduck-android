package com.montauk.voicecapture.tags

import com.montauk.voicecapture.session.LiveTranscriptLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A `live-transcript.jsonl` event line for the current displayed-tags set,
 * appended every time that set changes: `{"t_ms": 1200, "event": "tags",
 * "tags": [{"tag": "marathon training", "confidence": 0.87, "rank": 1}]}`.
 *
 * Distinct in shape from [LiveTranscriptLine] (transcript segments) and from
 * [com.montauk.voicecapture.session.ModeEventLine] (mode changes) -- same
 * contract-tolerant pattern as that class: a reader that only cares about
 * transcript text decodes as [LiveTranscriptLine] and simply skips any line
 * that doesn't match that shape (see
 * [com.montauk.voicecapture.session.SessionStore.readTranscriptLines]). An
 * offline organizer that *does* care about tags derives per-tag intervals by
 * scanning consecutive "tags" event lines.
 */
@Serializable
data class TagEventEntry(val tag: String, val confidence: Double, val rank: Int)

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
    // omit.
    private val json = Json { encodeDefaults = true }

    fun encodeLine(tMs: Long, displayed: List<DisplayedTag>): String {
        val line = TagsEventLine(
            tMs = tMs,
            tags = displayed.map { TagEventEntry(tag = it.tag, confidence = it.confidence, rank = it.rank) },
        )
        return json.encodeToString(TagsEventLine.serializer(), line)
    }
}
