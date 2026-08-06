package com.montauk.voicecapture.service

/**
 * Bead vn-edu.56: word count of a session's finalized live transcript so
 * far, feeding [TooShortPolicy]'s word-count trigger. Split out as a plain
 * function (rather than staying a private [RecordingService] method) so
 * it's directly unit-testable.
 */
object TranscriptWordCount {
    private val WHITESPACE = Regex("\\s+")

    /** Counts whitespace-separated tokens across all [lines]' text, ignoring blank lines/runs of whitespace. */
    fun count(lines: List<TranscriptLine>): Int =
        lines.sumOf { line -> line.text.trim().split(WHITESPACE).count { it.isNotBlank() } }
}
