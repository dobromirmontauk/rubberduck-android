package com.montauk.voicecapture.session

/**
 * Builds the transcript excerpt fed to [AnthropicTitleGenerator]'s prompt.
 * Pure string logic, unit-tested independent of any HTTP/coroutine
 * machinery -- same split as [DerivedTitle] vs. [SessionStore].
 *
 * Per bead vn-edu.42: the first ~[HEAD_LINES] lines establish what the
 * session opened with, the last ~[TAIL_LINES] establish how it wrapped up
 * -- enough context for a natural title without spending tokens on a full
 * 30-60 minute transcript. Short sessions (at or under [HEAD_LINES] +
 * [TAIL_LINES] total) are sent in full; there's no "middle" to drop.
 */
object TitlePromptBuilder {
    private const val HEAD_LINES = 15
    private const val TAIL_LINES = 10
    private const val ELISION_MARKER = "[...]"

    /** [finalLineTexts] is every final transcript line's text, oldest to newest. Returns null if empty -- nothing to title. */
    fun buildTranscriptExcerpt(finalLineTexts: List<String>): String? {
        if (finalLineTexts.isEmpty()) return null
        if (finalLineTexts.size <= HEAD_LINES + TAIL_LINES) return finalLineTexts.joinToString("\n")

        val head = finalLineTexts.take(HEAD_LINES)
        val tail = finalLineTexts.takeLast(TAIL_LINES)
        return (head + ELISION_MARKER + tail).joinToString("\n")
    }
}
