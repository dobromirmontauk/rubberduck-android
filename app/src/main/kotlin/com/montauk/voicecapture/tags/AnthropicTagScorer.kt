package com.montauk.voicecapture.tags

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Default MAJOR-topic [TagScorer]: Anthropic's Messages API (Claude Haiku)
 * scores a rolling transcript tail every ~[minIntervalMs] -- pennies/hour at
 * this cadence, see README. `anthropic.apiKey` follows the exact same
 * `local.properties` -> `BuildConfig` secret pattern as
 * `assemblyai.apiKey` in `app/build.gradle.kts`.
 *
 * Never throws: any failure (network error, non-200, unparseable/malformed
 * JSON) degrades silently to [fallback] (a [HeuristicTagScorer] by default)
 * -- a flaky network or an API hiccup should never be visible on the
 * recording screen. All I/O runs on [Dispatchers.IO], called from a
 * coordinator's own coroutine, never from the audio capture thread -- a
 * slow request only delays this scorer's next update, nothing else.
 */
class AnthropicTagScorer(
    private val apiKey: String,
    private val fallback: TagScorer = HeuristicTagScorer(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
    private val model: String = "claude-haiku-4-5-20251001",
) : TagScorer {

    override val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS

    override suspend fun score(transcriptTail: String, currentCandidates: List<String>): List<TagCandidate> {
        if (apiKey.isBlank() || transcriptTail.isBlank()) return fallback.score(transcriptTail, currentCandidates)

        val result = runCatching { withContext(Dispatchers.IO) { requestAndParse(transcriptTail, currentCandidates) } }
            .onFailure { e -> Log.w(TAG, "AnthropicTagScorer request threw: ${e.message}") }
            .getOrNull()

        if (result == null) {
            Log.w(TAG, "AnthropicTagScorer call failed or returned unparseable JSON; degrading to fallback")
            return fallback.score(transcriptTail, currentCandidates)
        }
        return result
    }

    private fun requestAndParse(transcriptTail: String, currentCandidates: List<String>): List<TagCandidate>? {
        val requestBody = buildRequestBody(transcriptTail, currentCandidates)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "AnthropicTagScorer HTTP ${response.code}")
                return null
            }
            val bodyText = response.body?.string() ?: return null
            return parseCandidates(bodyText)
        }
    }

    private fun buildRequestBody(transcriptTail: String, currentCandidates: List<String>): String {
        val candidatesNote = if (currentCandidates.isEmpty()) "None yet." else currentCandidates.joinToString(", ")
        val userContent = """
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

        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 300)
            put("system", SYSTEM_PROMPT)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", userContent)
                }
            }
        }
        return payload.toString()
    }

    /**
     * Navigates the response as [kotlinx.serialization.json.JsonElement]
     * rather than decoding into a strict data-class envelope, so an
     * unexpected/extra field in Anthropic's response shape can't fail
     * parsing on its own -- only a missing `content`/`text`/`tags` path, or
     * genuinely non-JSON model output, does. Returns null (never throws) on
     * any of those; [score] treats null as "degrade to fallback".
     */
    private fun parseCandidates(responseBody: String): List<TagCandidate>? {
        val root = runCatching { Json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return null
        val text = root["content"]?.jsonArray
            ?.firstNotNullOfOrNull { block -> runCatching { block.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull() }
            ?: return null
        val tagsObject = extractJsonObject(text) ?: return null
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
        const val ANTHROPIC_VERSION = "2023-06-01"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

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

/** Picks the real scorer vs. the keyless fallback for the current build -- mirrors [com.montauk.voicecapture.stt.SttClientFactory]. */
object TagScorerFactory {
    /** [apiKey] is `BuildConfig.ANTHROPIC_API_KEY`; blank means "not configured". */
    fun create(apiKey: String): TagScorer =
        if (apiKey.isBlank()) HeuristicTagScorer() else AnthropicTagScorer(apiKey)
}
