package com.montauk.voicecapture.tags

/**
 * Keyless fallback [TagScorer] (no network, no Android dependency): a
 * conservative noun-phrase-ish extractor over the rolling transcript tail.
 * Used whenever `anthropic.apiKey` isn't configured (see
 * [AnthropicTagScorer]) and as the degrade-to target if that scorer fails.
 *
 * Deliberately biased toward showing *fewer, stronger* tags rather than
 * filling all 3 slots with weak guesses -- "one strong tag beats three weak
 * ones" per spec. Two signals combine into a phrase's raw score:
 *  - **Repetition**: a two-word phrase (both content words, i.e. not
 *    stopwords) that recurs across the window is a much stronger MAJOR-topic
 *    signal than something said once.
 *  - **Topic-announcement position**: spoken narration tends to front-load
 *    the subject of a new beat ("Marathon training update...", "Nutrition
 *    has been tricky too...") -- the leading content word(s) of each
 *    sentence get a bonus for exactly this reason, independent of how often
 *    they recur later in the same window.
 * Neither signal alone is enough to fill a slot; the exponential-ish
 * normalization below keeps a single-mention phrase's confidence well under
 * [TagTracker]'s entry bar for slots 2/3, and even for slot 1 unless it also
 * carries the position bonus.
 */
class HeuristicTagScorer : TagScorer {

    // Runs fully offline and near-instantly, so it can be re-scored on
    // nearly every final transcript line -- the coordinator still throttles
    // it a little just to avoid pointless recompute on every single word.
    override val minIntervalMs: Long = 3_000L

    override suspend fun score(transcriptTail: String, currentCandidates: List<String>): List<TagCandidate> {
        val sentences = SENTENCE_SPLIT.split(transcriptTail).filter { it.isNotBlank() }
        val phraseScores = LinkedHashMap<String, Double>()

        for (sentence in sentences) {
            val words = WORD_PATTERN.findAll(sentence.lowercase()).map { it.value }.toList()
            if (words.isEmpty()) continue

            for (i in 0 until words.size - 1) {
                val a = words[i]
                val b = words[i + 1]
                if (isContentWord(a) && isContentWord(b)) {
                    val phrase = "$a $b"
                    phraseScores[phrase] = (phraseScores[phrase] ?: 0.0) + REPEAT_WEIGHT
                }
            }

            val leadIdx = words.indexOfFirst { isContentWord(it) }
            if (leadIdx >= 0) {
                val lead = if (leadIdx + 1 < words.size && isContentWord(words[leadIdx + 1])) {
                    "${words[leadIdx]} ${words[leadIdx + 1]}"
                } else {
                    words[leadIdx]
                }
                phraseScores[lead] = (phraseScores[lead] ?: 0.0) + LEAD_BONUS
            }
        }

        return phraseScores.entries
            .map { (phrase, raw) -> TagCandidate(phrase, normalizeConfidence(raw)) }
            .sortedByDescending { it.confidence }
            .take(MAX_RETURNED)
    }

    /** Diminishing returns on raw score so repetition alone can't runaway to 1.0, keeping "conservative" honest. */
    private fun normalizeConfidence(raw: Double): Double = (1.0 - Math.exp(-raw / NORMALIZE_SCALE)).coerceIn(0.0, 0.97)

    private fun isContentWord(word: String): Boolean =
        word.length >= MIN_WORD_LENGTH && word !in STOPWORDS && word !in NUMBER_WORDS && !word.all(Char::isDigit)

    private companion object {
        const val MIN_WORD_LENGTH = 3
        const val MAX_RETURNED = 10
        // Kept below LEAD_BONUS so a single mention of a phrase in
        // topic-announcement position outranks a bigram that merely happens
        // to repeat as an accidental word chain within one long sentence
        // (e.g. "keep getting", "getting harder") -- real repetition across
        // multiple sentences still adds up and wins on its own.
        const val REPEAT_WEIGHT = 0.6
        const val LEAD_BONUS = 0.85
        const val NORMALIZE_SCALE = 1.35

        val SENTENCE_SPLIT = Regex("[.!?]+")
        val WORD_PATTERN = Regex("[a-z0-9']+")

        // Numeral/measurement words rarely name a topic themselves ("twelve
        // weeks", "six miles", "four hours" are quantities, not subjects) --
        // excluding them keeps a long numeric aside from dominating the
        // phrase chain it sits in.
        val NUMBER_WORDS: Set<String> = setOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
            "eighty", "ninety", "hundred", "thousand", "million", "billion", "first", "second",
            "third", "half", "dozen",
        )

        // Deliberately the same broad-coverage list as the v1 TopicCloud this
        // scorer replaces -- function words, pronouns, auxiliaries, and
        // spoken-language filler, so phrases surface actual subject matter
        // instead of sentence scaffolding.
        val STOPWORDS: Set<String> = setOf(
            "the", "and", "for", "that", "this", "these", "those", "with", "have", "has", "had",
            "not", "are", "was", "were", "will", "would", "could", "should", "shall", "can", "cannot",
            "so", "just", "about", "into", "onto", "from", "your", "yours", "yourself", "yourselves",
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
}
