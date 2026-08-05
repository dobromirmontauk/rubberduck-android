package com.montauk.voicecapture.stt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire shapes for AssemblyAI's Universal-Streaming v3 WebSocket protocol
 * (https://www.assemblyai.com/docs/streaming/api-spec/streaming-websocket).
 * Only the fields this client actually uses are modelled; `ignoreUnknownKeys`
 * means the server can add fields without breaking parsing.
 *
 * Pulled into its own file (no OkHttp/WebSocket dependency) so the
 * message-to-[TranscriptPartial] mapping is coverable by plain JVM unit
 * tests without a fake socket.
 */
private val turnJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class WordSpan(
    val text: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val confidence: Double = 0.0,
    @SerialName("word_is_final") val wordIsFinal: Boolean = false,
)

@Serializable
internal data class TurnMessage(
    val type: String,
    @SerialName("turn_order") val turnOrder: Int = 0,
    @SerialName("end_of_turn") val endOfTurn: Boolean = false,
    @SerialName("turn_is_formatted") val turnIsFormatted: Boolean = false,
    val transcript: String = "",
    val words: List<WordSpan> = emptyList(),
)

/**
 * Parses one AssemblyAI WebSocket text message. Returns a [TranscriptPartial]
 * for `Turn` messages (the only ones the UI/transcript file care about);
 * `Begin`/`Termination`/anything else (including malformed JSON) yields null.
 *
 * `startMs`/`endMs` come from the first/last word span's `start`/`end`,
 * which AssemblyAI reports as milliseconds elapsed since the stream began --
 * i.e. already in the same "offset into the recording" units the vault
 * ingest contract's `t0_ms`/`t1_ms` expect. A turn with no words yet (rare,
 * very start of an utterance) falls back to 0/0.
 *
 * `stableText`/`unstableTail` (bead vn-edu.45) split [WordSpan.wordIsFinal]
 * into the prefix AssemblyAI has already committed to (won't be revised
 * further, even while the turn itself stays open) versus the still-forming
 * tail -- `takeWhile` rather than `filter` because word finality is
 * documented to only ever flip false-to-true moving forward through a turn's
 * word stream, never the reverse, so the split point is a single boundary,
 * not a scattered set.
 */
internal fun parseTurnMessage(raw: String): TranscriptPartial? {
    val message = runCatching { turnJson.decodeFromString(TurnMessage.serializer(), raw) }.getOrNull() ?: return null
    if (message.type != "Turn") return null
    val startMs = message.words.firstOrNull()?.start ?: 0L
    val endMs = message.words.lastOrNull()?.end ?: startMs
    val stableWords = message.words.takeWhile { it.wordIsFinal }
    val unstableWords = message.words.drop(stableWords.size)
    return TranscriptPartial(
        text = message.transcript,
        isFinal = message.endOfTurn,
        startMs = startMs,
        endMs = endMs,
        stableText = stableWords.joinToString(" ") { it.text },
        unstableTail = unstableWords.joinToString(" ") { it.text },
    )
}
