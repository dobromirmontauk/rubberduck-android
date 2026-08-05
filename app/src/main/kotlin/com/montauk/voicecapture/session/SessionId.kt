package com.montauk.voicecapture.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Session-id format shared with the voice-vault ingest contract:
 * `YYYY-MM-DD_HHMM_<4 random lowercase alnum>`, e.g. `2026-08-04_2311_a9k2`.
 *
 * Local wall-clock time is used (not UTC) so the id reads naturally to the
 * person who recorded the session; the `started_at` field in meta.json
 * carries the precise, timezone-unambiguous ISO-8601 instant.
 */
object SessionId {
    private const val SUFFIX_LENGTH = 4
    private val SUFFIX_ALPHABET = ('a'..'z') + ('0'..'9')
    private val FORMAT_PATTERN = "yyyy-MM-dd_HHmm"

    fun generate(now: Date = Date(), random: Random = Random.Default): String {
        val formatter = SimpleDateFormat(FORMAT_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val timestamp = formatter.format(now)
        val suffix = (1..SUFFIX_LENGTH).map { SUFFIX_ALPHABET.random(random) }.joinToString("")
        return "${timestamp}_$suffix"
    }

    private val VALID_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}_\d{4}_[a-z0-9]{$SUFFIX_LENGTH}$""")

    fun isValid(sessionId: String): Boolean = VALID_PATTERN.matches(sessionId)
}
