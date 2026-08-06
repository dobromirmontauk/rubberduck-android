package com.montauk.voicecapture.stt

/**
 * Keeps `live-transcript.jsonl` segment timestamps monotonic across a
 * deliberate STT socket reconnect (bead asn-r60's manual-pause resume: the
 * bead's spec literally requires the AssemblyAI stream to close on a hard
 * pause, so resuming means a brand-new [StreamingSttClient]/WebSocket, not
 * the same connection continuing).
 *
 * [TranscriptPartial.startMs]/[TranscriptPartial.endMs] come straight from
 * AssemblyAI's own turn-relative word timing (see [TurnMessage]), which is
 * relative to *that connection's* start -- a fresh connection's clock starts
 * back at ~0. Without correction, a post-reconnect turn's timestamps would
 * read as *earlier* than the pre-pause turns' in the same session, breaking
 * the "monotonic, maps to the trimmed audio timeline" contract this bead's
 * spec calls for. [RecordingService] calls [onReconnect] right before
 * establishing the new connection, and [toSessionMs] on every raw
 * partial/final timestamp before it's written to disk or published to the
 * UI, so every reconnect's clock gets folded into one ever-increasing
 * session-wide timeline.
 *
 * Deliberately scoped to *this bead's own* reconnect only: AssemblyAI's
 * existing built-in reconnect-on-drop (a real network hiccup, unrelated to
 * pause) already has the same latent reset-to-zero behavior today, but
 * fixing that is a separate, pre-existing concern this bead doesn't touch --
 * [AssemblyAiStreamingSttClient]'s own automatic reconnect path never calls
 * [onReconnect] here.
 */
class SttTimelineTracker {
    private var offsetMs: Long = 0L
    private var lastRawEndMs: Long = 0L

    /** Adds the accumulated offset to a raw, connection-relative timestamp from AssemblyAI. */
    fun toSessionMs(rawMs: Long): Long = rawMs + offsetMs

    /** Call for every final segment's raw (pre-[toSessionMs]) endMs, so [onReconnect] knows where the next connection's offset should pick up from. */
    fun recordRawEndMs(rawEndMs: Long) {
        lastRawEndMs = maxOf(lastRawEndMs, rawEndMs)
    }

    /** Call right before establishing a fresh connection after a deliberate close (this bead's manual-pause resume). */
    fun onReconnect() {
        offsetMs += lastRawEndMs
        lastRawEndMs = 0L
    }

    /** Call at the start of every new recording session so a previous session's offset never leaks in. */
    fun reset() {
        offsetMs = 0L
        lastRawEndMs = 0L
    }
}
