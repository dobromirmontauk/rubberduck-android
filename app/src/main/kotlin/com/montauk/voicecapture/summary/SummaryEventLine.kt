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
