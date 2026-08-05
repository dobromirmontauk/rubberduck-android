package com.montauk.voicecapture.session

/**
 * Session-list row title, derived from the first final transcript line so a
 * recent session is recognizable at a glance instead of just showing the
 * opaque session id. Pure string logic, unit-tested independent of
 * [SessionStore]'s file I/O.
 */
object DerivedTitle {
    const val UNTITLED = "untitled"
    private const val MAX_WORDS = 5
    private val LEADING_FILLER = setOf("um", "uh", "uhh", "umm", "so", "okay", "well", "like", "yeah")

    /** [firstFinalLineText] is the `text` of the first final line in `live-transcript.jsonl`, if any. */
    fun from(firstFinalLineText: String?): String {
        if (firstFinalLineText.isNullOrBlank()) return UNTITLED
        val words = firstFinalLineText.trim().split(Regex("\\s+"))
        var startIndex = 0
        while (startIndex < words.size && isFiller(words[startIndex])) {
            startIndex++
        }
        val meaningful = words.drop(startIndex).take(MAX_WORDS)
        if (meaningful.isEmpty()) return UNTITLED
        return meaningful.joinToString(" ")
    }

    private fun isFiller(word: String): Boolean =
        word.lowercase().trim(',', '.', '!', '?') in LEADING_FILLER
}
