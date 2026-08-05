package com.montauk.voicecapture.session

/**
 * Cleans up [AnthropicTitleGenerator]'s raw model response into a title
 * string, or null if the response isn't usable. Pure string logic,
 * unit-tested independent of any HTTP/coroutine machinery -- same split as
 * [DerivedTitle] vs. [SessionStore].
 *
 * The system prompt asks for a bare 4-8 word title with no quotes/fences/
 * trailing punctuation, but models don't always comply exactly -- this
 * strips the common ways they don't (wrapping quotes, a ```-fence, a
 * trailing period, multi-line output) and clamps an unexpectedly long
 * response rather than trusting it verbatim.
 */
object TitleResponseParser {
    // Generous relative to the 4-8 words asked for in the prompt -- a safety
    // clamp against a runaway/uncooperative response, not the normal case.
    private const val MAX_WORDS = 12

    /** Null if [rawText] is null/blank or has nothing left after cleanup. */
    fun parse(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        var cleaned = rawText.trim()
        cleaned = cleaned.removePrefix("```").removeSuffix("```").trim()
        // Collapse to a single line/space run -- a title is one line, however
        // many the model's raw output happened to wrap across.
        cleaned = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
        cleaned = stripWrappingQuotes(cleaned)
        cleaned = cleaned.trim().trimEnd('.', ' ')

        if (cleaned.isBlank()) return null

        val words = cleaned.split(" ")
        return if (words.size > MAX_WORDS) words.take(MAX_WORDS).joinToString(" ") else cleaned
    }

    private fun stripWrappingQuotes(text: String): String {
        if (text.length < 2) return text
        val pairs = listOf('"' to '"', '\'' to '\'')
        for ((open, close) in pairs) {
            if (text.first() == open && text.last() == close) return text.substring(1, text.length - 1).trim()
        }
        return text
    }
}
