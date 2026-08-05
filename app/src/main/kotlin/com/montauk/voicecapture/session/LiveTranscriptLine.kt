package com.montauk.voicecapture.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of `live-transcript.jsonl`, matching the voice-vault ingest
 * contract's schema exactly:
 * `{"t0_ms": 1200, "t1_ms": 2450, "text": "...", "final": true}`.
 *
 * Only `final: true` segments are ever written here (see
 * [com.montauk.voicecapture.stt.TranscriptPartial] KDoc) -- in-progress
 * partials are UI-only and never touch disk.
 */
@Serializable
data class LiveTranscriptLine(
    @SerialName("t0_ms") val t0Ms: Long,
    @SerialName("t1_ms") val t1Ms: Long,
    val text: String,
    val final: Boolean,
)

object LiveTranscriptWriter {
    // Compact (no pretty-print): the contract is one JSON object per line, not
    // one JSON object spread across several.
    private val json = Json

    fun encodeLine(line: LiveTranscriptLine): String = json.encodeToString(LiveTranscriptLine.serializer(), line)
}
