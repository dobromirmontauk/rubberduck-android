package com.montauk.voicecapture.tags

import android.util.Log
import com.montauk.voicecapture.llm.AnthropicClient
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Default MAJOR-topic [TagScorer]: Anthropic's Messages API (Claude Haiku)
 * scores a rolling transcript tail every ~[minIntervalMs] -- pennies/hour at
 * this cadence, see README. The effective key (runtime-entered via Settings/
 * the setup wizard, falling back to the `anthropic.apiKey` `local.properties`
 * -> `BuildConfig` dev convenience -- see [com.montauk.voicecapture.settings.AppSecretsStore.effectiveAnthropicKey],
 * bead vn-edu.48) is passed in as [apiKey].
 *
 * Never throws: any failure (network error, non-200, unparseable/malformed
 * JSON) degrades silently to [fallback] (a [NoOpTagScorer] by default, bead
 * vn-edu.46 superseding decision -- no heuristic guess, just no tags) -- a
 * flaky network or an API hiccup should never be visible on the recording
 * screen. All I/O runs on [Dispatchers.IO], called from a coordinator's own
 * coroutine, never from the audio capture thread -- a slow request only
 * delays this scorer's next update, nothing else.
 */
class AnthropicTagScorer(
    apiKey: String,
    private val fallback: TagScorer = NoOpTagScorer(),
    httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    endpoint: String = "https://api.anthropic.com/v1/messages",
    private val model: String = "claude-haiku-4-5-20251001",
) : TagScorer {

    private val client = AnthropicClient(apiKey, httpClient, endpoint)
    private val hasApiKey = apiKey.isNotBlank()

    override val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS

    override suspend fun score(transcriptTail: String, currentCandidates: List<String>): List<TagCandidate> {
        if (!hasApiKey || transcriptTail.isBlank()) return fallback.score(transcriptTail, currentCandidates)

        val rawText = client.complete(model, maxTokens = 300, systemPrompt = SYSTEM_PROMPT, userContent = buildUserContent(transcriptTail, currentCandidates))
        val result = rawText?.let { parseCandidates(it) }

        if (result == null) {
            Log.w(TAG, "AnthropicTagScorer call failed or returned unparseable JSON; degrading to fallback")
            return fallback.score(transcriptTail, currentCandidates)
        }
        return result
    }

    private fun buildUserContent(transcriptTail: String, currentCandidates: List<String>): String {
        val candidatesNote = if (currentCandidates.isEmpty()) "None yet." else currentCandidates.joinToString(", ")
        return """
            Rolling transcript excerpt (most recent speech, oldest to newest):
            ---
            $transcriptTail
            ---
            Tags currently being tracked: $candidatesNote

            Identify up to 5 MAJOR conversation topics discussed in this excerpt --
            broad subjects the speaker is actually talking about, not stray words.
            Prefer reusing an already-tracked tag's exact text over minting a
            near-duplicate synonym when it still applies.
        """.trimIndent()
    }

    /**
     * [rawText] is already the model's `content[0..].text` -- [AnthropicClient.complete]
     * extracts that envelope-navigation step out of this class (bead
     * vn-edu.42); this only has to parse *that* text as the `{"tags": [...]}`
     * shape. Navigates it as [kotlinx.serialization.json.JsonElement] rather
     * than decoding into a strict data-class envelope, so an unexpected/extra
     * field can't fail parsing on its own -- only a missing `tags` path, or
     * genuinely non-JSON model output, does. Returns null (never throws) on
     * any of those; [score] treats null as "degrade to fallback".
     */
    private fun parseCandidates(rawText: String): List<TagCandidate>? {
        val tagsObject = extractJsonObject(rawText) ?: return null
        val tagsArray = tagsObject["tags"]?.jsonArray ?: return null

        val candidates = tagsArray.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val tag = obj.getValue("tag").jsonPrimitive.content.trim()
                val confidence = obj.getValue("confidence").jsonPrimitive.double
                if (tag.isEmpty()) null else TagCandidate(tag, confidence.coerceIn(0.0, 1.0))
            }.getOrNull()
        }
        // A "tags" array that exists but decodes to zero usable entries (every
        // element malformed) is treated the same as no parseable response at
        // all, so the caller degrades to fallback instead of silently
        // clearing the display.
        return if (candidates.isEmpty() && tagsArray.isNotEmpty()) null else candidates
    }

    /** Strips a ```json fence Claude sometimes adds despite the system prompt's "no markdown fences" instruction. */
    private fun extractJsonObject(text: String): JsonObject? {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    private companion object {
        const val TAG = "AnthropicTagScorer"

        // Every ~25s per spec: frequent enough that tag chips feel live,
        // infrequent enough that even a 30-60 minute session costs pennies
        // on Haiku pricing -- see README's cost note.
        const val DEFAULT_MIN_INTERVAL_MS = 25_000L

        val SYSTEM_PROMPT = """
            You identify the MAJOR topics of an ongoing spoken conversation from a
            rolling transcript excerpt. Respond with ONLY strict JSON, no prose, no
            markdown fences, matching exactly this shape:
            {"tags":[{"tag":"<short topic label, 1-4 words>","confidence":<0.0-1.0>}]}
            Return at most 5 tags, most confident first. Confidence reflects how
            central and clearly-established the topic is in the excerpt, not how
            recently it was mentioned. Only include real subject-matter topics --
            never filler, stray words, or the speaker's name.
        """.trimIndent()
    }
}

/** Picks the real scorer vs. the keyless no-op for the current build -- mirrors [com.montauk.voicecapture.stt.SttClientFactory]. */
object TagScorerFactory {
    /**
     * [apiKey] is the effective Anthropic key (see [com.montauk.voicecapture.settings.AppSecretsStore.effectiveAnthropicKey]);
     * blank means "not configured". Bead vn-edu.46 superseding decision: keyless
     * returns [NoOpTagScorer], never [HeuristicTagScorer] -- no tags are
     * computed at all, and the UI shows the register-key message instead of
     * a heuristic's guess.
     */
    fun create(apiKey: String): TagScorer =
        if (apiKey.isBlank()) NoOpTagScorer() else AnthropicTagScorer(apiKey)
}
