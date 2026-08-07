package com.montauk.voicecapture.summary

import android.util.Log
import com.montauk.voicecapture.llm.AnthropicClient
import com.montauk.voicecapture.llm.PromptBlock
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Produces the model's PROPOSED updated bullet list for the live rolling
 * summary (bead asn-evl). Implementations must never throw; null means "this
 * round failed" (network error, non-200, unparseable response) --
 * [SummaryCoordinator] treats null as "keep the last good bullets and mark
 * the summary stale," never as something to surface as an error.
 *
 * The returned list is the model's own opinion of the full bullet list,
 * including its attempt at [previousBullets] -- it is NOT trusted to have
 * honored the append-only contract on its own. [AppendOnlyBulletMerge] is
 * what actually enforces that, one layer up in [SummaryCoordinator].
 */
interface SummaryGenerator {
    /**
     * [fullTranscript] is every finalized transcript line so far, joined
     * oldest to newest; [previousBullets] is the summary's current bullets,
     * in order. [discardedBullets] (bead asn-rrw) is every bullet the user
     * has swiped away this session, oldest first -- a soft ask ("please
     * don't write this again") the implementation should fold into its
     * prompt; [AppendOnlyBulletMerge]'s own discarded-set filter is the hard
     * backstop if a generator ignores this.
     */
    suspend fun generate(fullTranscript: String, previousBullets: List<String>, discardedBullets: List<String> = emptyList()): List<String>?
}

/**
 * Default [SummaryGenerator]: one Claude Haiku call per round, resending the
 * entire transcript every time (bead asn-evl's design: a live summary has to
 * see everything said so far, not a rolling tail like [com.montauk.voicecapture.tags.TagScorer]'s
 * topic scoring). [AnthropicClient.completeWithCache] marks both the system
 * prompt and the transcript block as prompt-caching breakpoints -- the
 * system prompt never changes call to call, and the transcript only ever
 * grows by appending, so almost all of each round's input tokens are served
 * from cache rather than re-billed at full price. Shares [AnthropicClient]
 * (same HTTP/secret plumbing [com.montauk.voicecapture.tags.AnthropicTagScorer]
 * and [com.montauk.voicecapture.session.AnthropicTitleGenerator] use) rather
 * than reimplementing the Messages API call.
 */
class AnthropicSummaryGenerator(
    apiKey: String,
    httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    endpoint: String = "https://api.anthropic.com/v1/messages",
    private val model: String = "claude-haiku-4-5-20251001",
) : SummaryGenerator {

    private val client = AnthropicClient(apiKey, httpClient, endpoint)

    override suspend fun generate(fullTranscript: String, previousBullets: List<String>, discardedBullets: List<String>): List<String>? {
        val systemBlocks = listOf(PromptBlock(SYSTEM_PROMPT, cacheControl = true))
        val userBlocks = listOfNotNull(
            // The transcript is the block that's worth caching -- it's the
            // large, monotonically-growing part of the prompt. The bullets
            // block is small and changes every round anyway, so it isn't
            // marked as a breakpoint.
            PromptBlock(transcriptBlock(fullTranscript), cacheControl = true),
            PromptBlock(bulletsBlock(previousBullets)),
            // Bead asn-rrw: only present once the user has actually
            // discarded something -- an empty block would just be noise on
            // every one of a session's early rounds.
            discardedBullets.takeIf { it.isNotEmpty() }?.let { PromptBlock(discardedBlock(it)) },
        )
        val rawText = client.completeWithCache(model, maxTokens = MAX_TOKENS, systemBlocks = systemBlocks, userBlocks = userBlocks)
        val bullets = rawText?.let { parseBullets(it) }
        if (bullets == null) {
            Log.w(TAG, "AnthropicSummaryGenerator call failed or returned unparseable JSON")
        }
        return bullets
    }

    private fun transcriptBlock(fullTranscript: String): String = """
        Full transcript so far (oldest to newest):
        ---
        $fullTranscript
        ---
    """.trimIndent()

    private fun bulletsBlock(previousBullets: List<String>): String {
        val listed = if (previousBullets.isEmpty()) "None yet." else previousBullets.joinToString("\n") { "- $it" }
        return """
            Current bullets, in order:
            $listed

            Return the updated bullet list per the system instructions.
        """.trimIndent()
    }

    /** Bead asn-rrw: told to the model so it doesn't re-propose a note the user explicitly discarded -- see [SummaryGenerator.generate]'s KDoc. */
    private fun discardedBlock(discardedBullets: List<String>): String {
        val listed = discardedBullets.joinToString("\n") { "- $it" }
        return """
            The user explicitly discarded these previously-suggested notes.
            Do not write any of them again, verbatim or reworded, even if the
            transcript still supports them:
            $listed
        """.trimIndent()
    }

    /** Strips a ```json fence Claude sometimes adds despite the system prompt's "no markdown fences" instruction -- same defensive parse as [com.montauk.voicecapture.tags.AnthropicTagScorer]. */
    private fun parseBullets(rawText: String): List<String>? {
        val trimmed = rawText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val array = root["bullets"]?.jsonArray ?: return null
        val bullets = array.mapNotNull { element -> runCatching { element.jsonPrimitive.content.trim() }.getOrNull() }
            .filter { it.isNotEmpty() }
        // A "bullets" array that exists but decodes to zero usable entries
        // (every element malformed) is a parse failure, not "the model says
        // there are no bullets" -- SummaryCoordinator only ever calls this
        // once the transcript has real content, so an empty result here
        // means something went wrong, not that there's genuinely nothing to
        // summarize yet.
        if (bullets.isEmpty() && array.isNotEmpty()) return null
        return bullets
    }

    private companion object {
        const val TAG = "AnthropicSummaryGenerator"
        // Room for a genuinely long-running conversation's bullet list plus
        // a handful of new ones each round -- generous relative to Haiku's
        // per-token cost at this cadence (see README's cost note for tags,
        // same order of magnitude here).
        const val MAX_TOKENS = 1024

        val SYSTEM_PROMPT = """
            You maintain a rolling bullet-point summary of an ongoing spoken
            conversation, built incrementally as more of it is transcribed.
            You are given the entire transcript so far and the summary's
            current bullets. Respond with ONLY strict JSON, no prose, no
            markdown fences, matching exactly this shape:
            {"bullets":["<bullet 1>","<bullet 2>", ...]}

            Hard stability contract: the current bullets you were given are
            already correct and already shown to the user -- return them
            FIRST, verbatim, in the same order, exactly as given. Only append
            brand-new bullets after them, for genuinely new information in
            the transcript that isn't covered by any existing bullet. Only
            change the wording of an existing bullet if it is now factually
            wrong given the transcript (this should be very rare) -- never
            rephrase, reorder, merge, or "clean up" an existing bullet just
            because you can.

            Each bullet is a short (5-15 words), concrete, third-person
            statement of one specific thing discussed -- never a heading,
            never a question, and never commentary about the summary itself.
            If nothing new and concrete has been said since the current
            bullets were written, return the current bullets unchanged and
            add nothing.
        """.trimIndent()
    }
}

/** Picks the real generator vs. no summaries at all for the current build -- mirrors [com.montauk.voicecapture.session.TitleGeneratorFactory]. */
object SummaryGeneratorFactory {
    /**
     * [apiKey] is the effective Anthropic key (see [com.montauk.voicecapture.settings.AppSecretsStore.effectiveAnthropicKey]);
     * blank means "not configured" -- returns null rather than a generator
     * that would just degrade to null on every call, so callers
     * ([com.montauk.voicecapture.service.RecordingService]) can skip the
     * summary pipeline entirely: keyless means summaries are silently off,
     * same contract as [TitleGeneratorFactory].
     */
    fun create(apiKey: String): SummaryGenerator? = if (apiKey.isBlank()) null else AnthropicSummaryGenerator(apiKey)
}
