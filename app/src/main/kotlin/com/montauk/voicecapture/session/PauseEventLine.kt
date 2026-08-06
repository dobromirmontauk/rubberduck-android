package com.montauk.voicecapture.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A `live-transcript.jsonl` event line for a pause/resume transition (bead
 * asn-r60): `{"t_ms": 1200, "event": "pause", "reason": "user"}`. [tMs] is on
 * the same trimmed-audio-timeline basis as [ModeEventLine]/[LiveTranscriptLine]
 * -- see [com.montauk.voicecapture.service.RecordingService]'s
 * `audioTimelineClock` KDoc for why that basis excludes paused spans
 * entirely (both hard and soft), matching what's actually persisted to
 * `audio.ogg`.
 *
 * [event] is always `"pause"` or `"resume"`; [reason] is always `"user"`
 * (manual/hard pause) or `"auto"` (VAD-driven/soft pause). Distinct in shape
 * from [LiveTranscriptLine]/[ModeEventLine]/[com.montauk.voicecapture.tags.TagsEventLine]
 * -- same contract-tolerant pattern as those: a reader that only cares about
 * transcript text decodes as [LiveTranscriptLine] and skips any line that
 * doesn't match that shape (see [SessionStore.readTranscriptLines]).
 */
@Serializable
data class PauseEventLine(
    @SerialName("t_ms") val tMs: Long,
    val event: String,
    val reason: String,
)

object PauseEventWriter {
    // Compact (no pretty-print), matching ModeEventWriter/TagsEventWriter:
    // one JSON object per physical line.
    private val json = Json

    fun encodeLine(tMs: Long, event: String, reason: String): String =
        json.encodeToString(PauseEventLine.serializer(), PauseEventLine(tMs = tMs, event = event, reason = reason))
}
