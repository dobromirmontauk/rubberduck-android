package com.montauk.voicecapture.stt.integration

/**
 * Normalized word-overlap between a source script and a received transcript,
 * for [AssemblyAiLiveStreamingTest]. Lowercases, strips punctuation, drops a
 * small stopword list, and drops spelled-out number words -- AssemblyAI's
 * formatter renders numbers as digits ("30,000") while the fixture scripts
 * spell them out ("thirty thousand"), and the digit forms already fall out
 * of the letters-only tokenizer below, so excluding the word forms too keeps
 * numeral rendering from being scored as a transcription miss.
 *
 * Scored as recall against the source script (fraction of the script's
 * distinct content words that show up somewhere in the transcript), not
 * Jaccard -- a verbose or reformatted transcript that still contains every
 * word of the script should score 1.0.
 */
internal fun wordOverlap(source: String, transcript: String): Double {
    val sourceTokens = normalizedTokens(source)
    if (sourceTokens.isEmpty()) return 1.0
    val transcriptTokens = normalizedTokens(transcript)
    return sourceTokens.intersect(transcriptTokens).size.toDouble() / sourceTokens.size
}

private val WORD_PATTERN = Regex("[a-zA-Z']+")

private fun normalizedTokens(text: String): Set<String> =
    WORD_PATTERN.findAll(text.lowercase())
        .map { it.value.trim('\'') }
        .filter { it.isNotBlank() }
        .filterNot { it in STOPWORDS }
        .filterNot { it in NUMBER_WORDS }
        .toSet()

private val NUMBER_WORDS: Set<String> = setOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
    "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    "hundred", "thousand", "million",
)

private val STOPWORDS: Set<String> = setOf(
    "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "been", "being",
    "to", "of", "in", "on", "at", "for", "with", "as", "by", "that", "this", "these", "those",
    "it", "its", "i", "my", "me", "we", "our", "you", "your", "he", "she", "they", "them",
    "so", "just", "then", "than", "too", "very", "also", "not", "no", "do", "does", "did",
    "have", "has", "had", "will", "would", "can", "could", "should", "shall", "if", "because",
    "about", "into", "over", "under", "after", "before", "during", "within", "again",
    "up", "down", "out", "off", "most", "more", "much", "many", "some", "all", "each", "along",
)
