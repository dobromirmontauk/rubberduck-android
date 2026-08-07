package com.montauk.voicecapture.summary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A `live-transcript.jsonl` event line for one live-summary round (bead
 * asn-evl), appended every time [SummaryCoordinator.onTick] returns a
 * non-null result: `{"t_ms": 60000, "event": "summary", "added":
 * ["Discussed moving the kickoff meeting to next Tuesday."]}`. `added` is
 * exactly [SummaryRoundResult.added] -- the bullets that landed *this*
 * round, not the full running list (that's [SummaryMarkdownWriter]'s job,
 * rewritten whole into `summary.md` each round) -- so an empty array here is
 * a real, meaningful event: a round ran and flipped the stale indicator
 * without adding anything.
 *
 * Distinct in shape from [com.montauk.voicecapture.tags.TagsEventLine] and
 * [com.montauk.voicecapture.session.ModeEventLine] -- same contract-tolerant
 * pattern as those: a reader that only cares about transcript text keeps
 * decoding as [com.montauk.voicecapture.session.LiveTranscriptLine] and
 * skips any line that doesn't match that shape.
 */
@Serializable
data class SummaryEventLine(
    @SerialName("t_ms") val tMs: Long,
    val event: String = "summary",
    val added: List<String>,
)

object SummaryEventWriter {
    // Compact (no pretty-print), matching TagsEventWriter/ModeEventWriter:
    // one JSON object per physical line. encodeDefaults=true for the same
    // reason as those -- "event" always carries "summary", which is exactly
    // the default-valued field kotlinx.serialization would otherwise omit.
    private val json = Json { encodeDefaults = true }

    fun encodeLine(tMs: Long, added: List<String>): String =
        json.encodeToString(SummaryEventLine.serializer(), SummaryEventLine(tMs = tMs, added = added))
}

/**
 * A `live-transcript.jsonl` event line for a user action on the notes card
 * (bead asn-rrw): swiping right approves a bullet, swiping left discards it.
 * `{"t_ms": 12000, "event": "summary_note", "approved": ["Budget cap set at
 * $80k"]}` or `{"t_ms": 12000, "event": "summary_note", "discarded":
 * ["Second bid $72k"]}` -- distinct `event` value from [SummaryEventLine]
 * ("summary") so a reader can tell a scorer-produced round apart from a
 * user's swipe action without inspecting any other field. Mutually exclusive
 * per line, same "one variant per line" shape as
 * [com.montauk.voicecapture.tags.TagsEventLine]'s model-snapshot/user-edit
 * split -- a discard also removes the bullet from [SummaryCoordinator]'s
 * running list (see [SummaryCoordinator.discardBullet]), so this line is the
 * only durable record that the note ever existed at all.
 */
@Serializable
data class SummaryNoteEventLine(
    @SerialName("t_ms") val tMs: Long,
    val event: String = "summary_note",
    val approved: List<String>? = null,
    val discarded: List<String>? = null,
)

object SummaryNoteEventWriter {
    // explicitNulls=false (matching TagsEventWriter) drops whichever of
    // approved/discarded is unset for a given line, rather than writing e.g.
    // "discarded":null alongside a populated "approved".
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun encodeApproved(tMs: Long, bulletText: String): String =
        json.encodeToString(SummaryNoteEventLine.serializer(), SummaryNoteEventLine(tMs = tMs, approved = listOf(bulletText)))

    fun encodeDiscarded(tMs: Long, bulletText: String): String =
        json.encodeToString(SummaryNoteEventLine.serializer(), SummaryNoteEventLine(tMs = tMs, discarded = listOf(bulletText)))
}
