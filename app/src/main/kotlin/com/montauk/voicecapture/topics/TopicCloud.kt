package com.montauk.voicecapture.topics

/**
 * On-phone keyword extraction over the rolling FINAL transcript, driving the
 * recording screen's topic chips (plan bead vn-edu.5). Pure Kotlin (no
 * Android dependency) so the extraction and recency-weighting math is
 * JVM-testable without Robolectric.
 *
 * Recomputed on each final turn from the whole rolling transcript so far;
 * [Line.endMs] is the audio-session-relative end timestamp (matches
 * [com.montauk.voicecapture.service.TranscriptLine.endMs]), compared against
 * [compute]'s `nowMs` (the recording's current elapsed time) to double-weight
 * anything spoken in roughly the last 90 seconds.
 */
object TopicCloud {

    data class Line(val text: String, val endMs: Long)

    enum class Tier { LARGE, MEDIUM, SMALL }

    data class Topic(val word: String, val weight: Double, val tier: Tier)

    const val MAX_TOPICS = 5
    private const val MIN_WORD_LENGTH = 3
    private const val RECENCY_WINDOW_MS = 90_000L
    private const val RECENCY_MULTIPLIER = 2.0

    private val WORD_PATTERN = Regex("[a-z0-9']+")

    /** Top [MAX_TOPICS] terms across [lines], largest weight first. */
    fun compute(lines: List<Line>, nowMs: Long): List<Topic> {
        val weights = LinkedHashMap<String, Double>()
        for (line in lines) {
            val ageMs = nowMs - line.endMs
            val multiplier = if (ageMs in 0..RECENCY_WINDOW_MS) RECENCY_MULTIPLIER else 1.0
            for (word in tokenize(line.text)) {
                weights[word] = (weights[word] ?: 0.0) + multiplier
            }
        }
        val ranked = weights.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(MAX_TOPICS)
        return ranked.mapIndexed { index, entry -> Topic(entry.key, entry.value, tierFor(index)) }
    }

    private fun tierFor(index: Int): Tier = when (index) {
        0 -> Tier.LARGE
        1, 2 -> Tier.MEDIUM
        else -> Tier.SMALL
    }

    private fun tokenize(text: String): List<String> =
        WORD_PATTERN.findAll(text.lowercase())
            .map { it.value.trim('\'') }
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOPWORDS }
            .toList()

    // Compact but broad-coverage English stopword list: function words,
    // pronouns, auxiliary verbs, and spoken-language filler ("um"/"uh"/
    // "like"/"okay") -- so topic chips surface actual subject-matter
    // nouns/verbs instead of sentence scaffolding.
    private val STOPWORDS: Set<String> = setOf(
        "the", "and", "for", "that", "this", "these", "those", "with", "have", "has", "had",
        "not", "are", "was", "were", "will", "would", "could", "should", "shall", "can", "cannot",
        "so",
        "just", "about", "into", "onto", "from", "your", "yours", "yourself", "yourselves",
        "them", "they", "their", "theirs", "there", "here", "what", "when", "where", "which",
        "who", "whom", "whose", "why", "how", "all", "any", "both", "each", "few", "more", "most",
        "other", "others", "some", "such", "only", "own", "same", "than", "too", "very", "now",
        "then", "once", "because", "while", "before", "after", "above", "below", "between",
        "under", "again", "further", "during", "out", "off", "over", "being", "doing", "does",
        "did", "done", "him", "his", "her", "hers", "she", "its", "our", "ours", "ourselves",
        "myself", "himself", "herself", "itself", "themselves", "been", "also", "really",
        "actually", "basically", "literally", "kind", "sort", "like", "okay", "yeah", "yes",
        "no", "nope", "um", "uh", "uhh", "umm", "gonna", "wanna", "gotta", "get", "got", "one",
        "two", "three", "think", "thing", "things", "know", "going", "said", "say", "says",
        "see", "saw", "look", "looking", "looked", "well", "right", "little", "much", "many",
        "every", "either", "neither", "nor", "but", "and/or", "or", "if", "unless", "until",
        "since", "you're", "youre", "i'm", "im", "it's", "its", "we're", "were", "isn't",
        "aren't", "wasn't", "weren't", "don't", "dont", "doesn't", "didn't", "won't", "wouldn't",
        "couldn't", "shouldn't", "can't", "cant",
    )
}
