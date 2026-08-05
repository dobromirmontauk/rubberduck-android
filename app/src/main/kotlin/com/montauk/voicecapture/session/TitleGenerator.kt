package com.montauk.voicecapture.session

import com.montauk.voicecapture.llm.AnthropicClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Produces a natural-language session title from its final transcript lines
 * (bead vn-edu.42). Implementations must never throw and must never take
 * meaningfully longer than they document -- a caller treats `null` as "keep
 * whatever title is already showing" ([DerivedTitle]'s words, via
 * [SessionStore.listSessions]'s title-preference chain), never as an error
 * to surface.
 */
interface TitleGenerator {
    /** [finalLineTexts] is every final transcript line's text, oldest to newest. Null on any failure, timeout, or an empty transcript. */
    suspend fun generate(finalLineTexts: List<String>): String?
}

/**
 * Default [TitleGenerator]: one Claude Haiku call over
 * [TitlePromptBuilder]'s head+tail transcript excerpt, cleaned up by
 * [TitleResponseParser]. Shares [AnthropicClient] (the same HTTP/secret
 * plumbing [com.montauk.voicecapture.tags.AnthropicTagScorer] uses) rather
 * than reimplementing the Messages API call.
 *
 * Bounded by [timeoutMs] (default 5s per the bead's acceptance criteria) --
 * enforced two ways, deliberately redundant: [httpClient]'s own `callTimeout`
 * is OkHttp's independent watchdog, which aborts the underlying blocking
 * socket call regardless of what it's doing (unlike Kotlin coroutine
 * cancellation, which only takes effect at a suspension point -- a plain
 * blocking `execute()` call never reaches one, so [withTimeoutOrNull] alone
 * would NOT actually bound a truly hung connection); [withTimeoutOrNull]
 * around the call is a second, harmless backstop. So a slow/hung response
 * can never meaningfully delay whatever triggered this call --
 * [com.montauk.voicecapture.service.RecordingService.endRecording] fires
 * this off on its own scope specifically so that holds even in the worst
 * case, never blocking stop/finalize/upload.
 */
class AnthropicTitleGenerator(
    apiKey: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build(),
    endpoint: String = "https://api.anthropic.com/v1/messages",
    private val model: String = "claude-haiku-4-5-20251001",
) : TitleGenerator {

    private val client = AnthropicClient(apiKey, httpClient, endpoint)

    override suspend fun generate(finalLineTexts: List<String>): String? {
        val excerpt = TitlePromptBuilder.buildTranscriptExcerpt(finalLineTexts) ?: return null
        val rawText = withTimeoutOrNull(timeoutMs) {
            client.complete(model, maxTokens = MAX_TOKENS, systemPrompt = SYSTEM_PROMPT, userContent = excerpt)
        } ?: return null
        return TitleResponseParser.parse(rawText)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MAX_TOKENS = 40

        val SYSTEM_PROMPT = """
            You write short, natural-language titles for a transcribed spoken
            conversation, given an excerpt of it. Respond with ONLY the title --
            no quotes, no markdown, no trailing punctuation, no preamble like
            "Title:". The title must be 4 to 8 words, written the way a person
            would describe the conversation to someone else afterward (e.g.
            "Kitchen remodel bids and layout call"), not a generic label like
            "Voice memo" or "Conversation transcript".
        """.trimIndent()
    }
}

/** Picks the real generator vs. no generation at all for the current build -- mirrors [com.montauk.voicecapture.tags.TagScorerFactory]. */
object TitleGeneratorFactory {
    /**
     * [apiKey] is `BuildConfig.ANTHROPIC_API_KEY`; blank means "not
     * configured" -- returns null rather than a generator that would just
     * degrade to null on every call, so callers can skip the async title
     * task entirely and match today's keyless behavior exactly.
     */
    fun create(apiKey: String): TitleGenerator? = if (apiKey.isBlank()) null else AnthropicTitleGenerator(apiKey)
}
