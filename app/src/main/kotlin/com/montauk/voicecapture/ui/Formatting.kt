package com.montauk.voicecapture.ui

import com.montauk.voicecapture.session.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d min %02d sec", minutes, seconds)
}

private val ROW_DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())

/** "Aug 5 · 12 min 03 sec · 2026-08-05_2311_a9k2" -- the session-list row meta line. */
fun formatSessionMetaLine(session: SessionSummary): String =
    "${ROW_DATE_FORMAT.format(session.startedAt)} · ${formatDuration(session.durationMs)} · ${session.sessionId}"

private val AS_OF_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** "10:32" -- the Sessions screen's stale-vault-data caption (bead vn-edu.54). */
fun formatAsOfTime(epochMs: Long): String = AS_OF_TIME_FORMAT.format(Date(epochMs))

/** "[03:41]" prefix for a live-transcript paragraph, from its t0_ms. */
fun formatTranscriptTimestamp(t0Ms: Long): String {
    val totalSeconds = t0Ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "[%02d:%02d]", minutes, seconds)
}
