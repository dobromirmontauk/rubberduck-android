package com.montauk.voicecapture.llm

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One text block of a [AnthropicClient.completeWithCache] prompt.
 * [cacheControl] `true` marks this block as a prompt-caching breakpoint
 * (`cache_control: {"type": "ephemeral"}`) -- Anthropic caches everything up
 * to and including the last such breakpoint in a request and reuses it on a
 * later request sharing the same prefix verbatim, at a fraction of the
 * cost/latency of a full re-read. Bead asn-evl: a system-prompt block that
 * never changes call to call is always a cache hit after the first round; a
 * transcript block that only ever grows by appending is a *partial* hit each
 * round (the unchanged prefix is served from cache, only the newly-appended
 * tail is billed as fresh input) -- this is what makes resending the entire
 * transcript every round affordable.
 */
data class PromptBlock(val text: String, val cacheControl: Boolean = false)

/**
 * Minimal shared HTTP plumbing for Anthropic's Messages API -- extracted
 * from [com.montauk.voicecapture.tags.AnthropicTagScorer] (bead vn-edu.38)
 * so a second caller ([com.montauk.voicecapture.session.AnthropicTitleGenerator],
 * bead vn-edu.42) doesn't reimplement the same endpoint/headers/timeout/
 * top-level-response-shape logic. Deliberately thin: only "send this system
 * + user prompt, get the model's raw text back (or null on any failure)"
 * lives here -- what that text *means* (a tags JSON blob vs. a one-line
 * title) is each caller's own business, decoded downstream of [complete].
 *
 * Never throws: any failure (blank key, network error, non-200, unparseable
 * JSON, missing `content[0].text`) degrades to `null`. Callers are expected
 * to treat `null` as "fall back to whatever this call would have improved
 * on" -- never to surface a failure to the user.
 */
class AnthropicClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
) {
    /**
     * Returns the concatenated text of the response's `content` blocks, or
     * null if [apiKey] is blank (no request is even attempted), the request
     * fails, the response isn't 200, or the response has no text content.
     * Runs on [Dispatchers.IO] regardless of the caller's own dispatcher.
     */
    suspend fun complete(model: String, maxTokens: Int, systemPrompt: String, userContent: String): String? {
        if (apiKey.isBlank()) return null
        return runCatching { withContext(Dispatchers.IO) { requestAndExtractText(model, maxTokens, systemPrompt, userContent) } }
            .onFailure { e -> Log.w(TAG, "AnthropicClient request threw: ${e.message}") }
            .getOrNull()
    }

    /**
     * Same contract as [complete] (never throws, null on any failure) but
     * with [systemBlocks]/[userBlocks] each rendered as an Anthropic content
     * block array instead of a single string, so callers can mark a
     * [PromptBlock.cacheControl] breakpoint -- see [PromptBlock]'s KDoc.
     * Bead asn-evl's first caller ([com.montauk.voicecapture.summary.AnthropicSummaryGenerator])
     * needs this because its transcript-plus-previous-bullets prompt is
     * resent in full every round; the plain [complete] has no way to mark a
     * cacheable prefix.
     */
    suspend fun completeWithCache(model: String, maxTokens: Int, systemBlocks: List<PromptBlock>, userBlocks: List<PromptBlock>): String? {
        if (apiKey.isBlank()) return null
        return runCatching { withContext(Dispatchers.IO) { requestAndExtractTextWithCache(model, maxTokens, systemBlocks, userBlocks) } }
            .onFailure { e -> Log.w(TAG, "AnthropicClient cached request threw: ${e.message}") }
            .getOrNull()
    }

    private fun requestAndExtractText(model: String, maxTokens: Int, systemPrompt: String, userContent: String): String? {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", userContent)
                }
            }
        }
        return executeAndExtractText(payload)
    }

    /**
     * [systemBlocks]/[userBlocks] render as Anthropic's content-block-array
     * shape (`[{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]`)
     * rather than [requestAndExtractText]'s plain strings -- see [PromptBlock].
     */
    private fun requestAndExtractTextWithCache(
        model: String,
        maxTokens: Int,
        systemBlocks: List<PromptBlock>,
        userBlocks: List<PromptBlock>,
    ): String? {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            putJsonArray("system") { systemBlocks.forEach { addTextBlock(it) } }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") { userBlocks.forEach { addTextBlock(it) } }
                }
            }
        }
        return executeAndExtractText(payload)
    }

    private fun JsonArrayBuilder.addTextBlock(block: PromptBlock) {
        addJsonObject {
            put("type", "text")
            put("text", block.text)
            if (block.cacheControl) {
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
    }

    private fun executeAndExtractText(payload: JsonObject): String? {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "AnthropicClient HTTP ${response.code}")
                return null
            }
            val bodyText = response.body?.string() ?: return null
            return extractText(bodyText)
        }
    }

    private fun extractText(responseBody: String): String? {
        val root = runCatching { Json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return null
        return root["content"]?.jsonArray
            ?.mapNotNull { block -> runCatching { block.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("")
    }

    private companion object {
        const val TAG = "AnthropicClient"
        const val ANTHROPIC_VERSION = "2023-06-01"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
