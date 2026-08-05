package com.montauk.voicecapture.session

/**
 * The three recording modes from the mode switcher (recording screen). Only
 * [LISTEN] does anything today -- [CONVERSE] and [CHALLENGE] are visible but
 * disabled in the UI ("soon" tag) until Phase 6 wires up their actual
 * behavior (voice-agent talk-back and devil's-advocate prompting,
 * respectively). [wireValue] is what's written to `live-transcript.jsonl`
 * event lines and `meta.json`'s `modes` list -- keep it snake_case-free and
 * stable, since the vault ingest side reads it too.
 */
enum class RecordingMode(val wireValue: String, val isEnabled: Boolean) {
    LISTEN("listen", isEnabled = true),
    CONVERSE("converse", isEnabled = false),
    CHALLENGE("challenge", isEnabled = false),
    ;

    companion object {
        val DEFAULT = LISTEN

        fun fromWireValue(value: String): RecordingMode? = entries.firstOrNull { it.wireValue == value }
    }
}
