package com.montauk.voicecapture.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One diarized utterance from `sessions/<id>/transcript.json`'s `utterances`
 * array (bead vn-edu.57) -- the pass-2 canonical transcript
 * `voice-vault/scripts/transcribe.py` writes per the ingest contract. Field
 * names mirror the AssemblyAI response shape verbatim (`t0_ms`/`t1_ms`, a raw
 * diarization letter like `"A"`/`"B"` for [speaker]); there is no
 * voice-enrollment speaker-naming yet, so [speaker] is shown as-is.
 */
@Serializable
data class CanonicalUtterance(
    @SerialName("t0_ms") val t0Ms: Long = 0,
    @SerialName("t1_ms") val t1Ms: Long = 0,
    val speaker: String? = null,
    val text: String = "",
)

/** The Canonical tab's render input -- just the utterances; `transcript.json`'s word-level `words` array isn't needed at this granularity. */
data class CanonicalTranscript(val utterances: List<CanonicalUtterance>) {
    companion object {
        val EMPTY = CanonicalTranscript(emptyList())
    }
}

/**
 * Tolerant parser for `transcript.json` (bead vn-edu.57). Never throws:
 * blank/null input, malformed JSON, a missing or null `utterances` key, and
 * individual utterances missing `text` all degrade gracefully (to an empty
 * transcript, or to a transcript with that one utterance dropped) rather
 * than failing the whole Canonical tab -- `transcript.json` is produced by a
 * separate pipeline (`voice-vault/scripts/transcribe.py`) this app doesn't
 * control the exact shape of at every future revision.
 */
object CanonicalTranscriptParser {
    @Serializable
    private data class RawTranscript(val utterances: List<CanonicalUtterance>? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): CanonicalTranscript {
        if (raw.isNullOrBlank()) return CanonicalTranscript.EMPTY
        val decoded = runCatching { json.decodeFromString<RawTranscript>(raw) }.getOrNull()
            ?: return CanonicalTranscript.EMPTY
        val utterances = (decoded.utterances ?: emptyList()).filter { it.text.isNotBlank() }
        return CanonicalTranscript(utterances)
    }
}
