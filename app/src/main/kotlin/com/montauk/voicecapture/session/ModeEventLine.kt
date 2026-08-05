package com.montauk.voicecapture.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A `live-transcript.jsonl` event line, distinct in shape from
 * [LiveTranscriptLine] (transcript segments): `{"t_ms": 1200, "event":
 * "mode", "mode": "listen"}`. Currently the only event type is "mode" (see
 * [RecordingModeStateMachine]) -- readers that only care about transcript
 * text must keep filtering on `final: true` / decoding as
 * [LiveTranscriptLine] and simply skip lines that don't match that shape,
 * the way [SessionStore.readTranscriptLines] already does.
 */
@Serializable
data class ModeEventLine(
    @SerialName("t_ms") val tMs: Long,
    val event: String = "mode",
    val mode: String,
)

object ModeEventWriter {
    // Compact (no pretty-print), matching LiveTranscriptWriter: one JSON
    // object per physical line, not spread across several. encodeDefaults is
    // required here -- every event line always carries the same "event":
    // "mode" value, which is exactly the case kotlinx.serialization's default
    // (omit fields left at their default) would otherwise drop, per the same
    // gotcha SessionStore.writeMeta's json config is guarding against.
    private val json = Json { encodeDefaults = true }

    fun encodeLine(change: ModeChange): String =
        json.encodeToString(ModeEventLine.serializer(), ModeEventLine(tMs = change.tMs, mode = change.mode.wireValue))
}
