package com.montauk.voicecapture.tags

import android.util.Log
import com.montauk.voicecapture.llm.AnthropicClient
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * Bead vn-edu.47: when [score]'s `tree` argument is non-[TagTree.EMPTY], the
 * prompt is anchored to it -- the model is given every active node's id,
 * full ancestor path, and description, and asked to match speech against
 * those nodes first, proposing a genuinely new tag only when nothing fits
 * (mirrors the vault's own organize skill's "confident new subtopic" bar).
 * With an empty tree (no vault configured), the prompt is byte-identical to
 * this class's pre-vn-edu.47 behavior -- plain free-form topic labels, no
 * `tag_id`, no proposal marking.
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

    override suspend fun score(transcriptTail: String, currentCandidates: List<String>, tree: TagTree): List<TagCandidate> {
        if (!hasApiKey || transcriptTail.isBlank()) return fallback.score(transcriptTail, currentCandidates, tree)

        val systemPrompt = if (tree.isEmpty) LEGACY_SYSTEM_PROMPT else TREE_SYSTEM_PROMPT
        val userContent = buildUserContent(transcriptTail, currentCandidates, tree)
        // Bead asn-k7i cause E: client.complete() returning null means the
        // HTTP call itself failed (network error, non-200, or an
        // unusable/empty response envelope -- see AnthropicClient's KDoc),
        // which at this ~5s cadence is often just one transient blip. A
        // single immediate retry -- no backoff, no third attempt -- recovers
        // that cycle instead of silently costing it; a second failure still
        // degrades to fallback exactly as before.
        val rawText = client.complete(model, maxTokens = 400, systemPrompt = systemPrompt, userContent = userContent)
            ?: client.complete(model, maxTokens = 400, systemPrompt = systemPrompt, userContent = userContent)
        val result = rawText?.let { parseCandidates(it, tree) }

        if (result == null) {
            Log.w(TAG, "AnthropicTagScorer call failed or returned unparseable JSON; degrading to fallback")
            return fallback.score(transcriptTail, currentCandidates, tree)
        }
        return result
    }

    private fun buildUserContent(transcriptTail: String, currentCandidates: List<String>, tree: TagTree): String {
        val candidatesNote = if (currentCandidates.isEmpty()) "None yet." else currentCandidates.joinToString(", ")
        val treeSection = if (tree.isEmpty) "" else buildTreeSection(tree)
        return """
            Rolling transcript excerpt (most recent speech, oldest to newest):
            ---
            $transcriptTail
            ---
            Tags currently being tracked: $candidatesNote
            $treeSection
            Identify up to 5 MAJOR conversation topics discussed in this excerpt --
            broad subjects the speaker is actually talking about, not stray words.
            Prefer reusing an already-tracked tag's exact text over minting a
            near-duplicate synonym when it still applies.
        """.trimIndent()
    }

    private fun buildTreeSection(tree: TagTree): String {
        val lines = tree.activeNodes().joinToString("\n") { node ->
            "${node.id} | ${tree.path(node.id)} | ${node.description}"
        }
        return """

            Existing tag tree (id | full path | description) -- match against
            these nodes first, by name, path, or description:
            $lines
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
     *
     * Bead vn-edu.47: a `tag_id` the model returned is only honored if it
     * actually resolves in [tree] -- a hallucinated or stale id degrades to
     * a plain free-form candidate (not a dropped one) rather than carrying
     * a dangling reference downstream. When a `tag_id` does resolve, the
     * matched node's own canonical name overrides whatever text the model
     * put in "tag", so display text can never drift from the tree even if
     * the model paraphrases it. [TagCandidateGating.enforceAtMostOneProposal]
     * is the backstop for "at most one clearly-marked proposal."
     */
    private fun parseCandidates(rawText: String, tree: TagTree): List<TagCandidate>? {
        val tagsObject = extractJsonObject(rawText) ?: return null
        val tagsArray = tagsObject["tags"]?.jsonArray ?: return null

        val candidates = tagsArray.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val matchedNode = rawTagId(obj)?.let { tree.node(it) }
                val tag = matchedNode?.name ?: obj.getValue("tag").jsonPrimitive.content.trim()
                val confidence = obj.getValue("confidence").jsonPrimitive.double
                // Guarded on !tree.isEmpty too, not just matchedNode == null:
                // the legacy (no-vault) prompt never asks for "is_proposal"
                // at all, so a response to that prompt must never end up
                // proposal-flagged even if a model coincidentally echoed
                // such a key back.
                val isProposal = !tree.isEmpty && matchedNode == null && (obj["is_proposal"]?.jsonPrimitive?.booleanOrNull ?: false)
                if (tag.isEmpty()) null else TagCandidate(tag, confidence.coerceIn(0.0, 1.0), tagId = matchedNode?.id, isProposal = isProposal)
            }.getOrNull()
        }
        // A "tags" array that exists but decodes to zero usable entries (every
        // element malformed) is treated the same as no parseable response at
        // all, so the caller degrades to fallback instead of silently
        // clearing the display.
        if (candidates.isEmpty() && tagsArray.isNotEmpty()) return null
        return TagCandidateGating.enforceAtMostOneProposal(candidates)
    }

    private fun rawTagId(obj: JsonObject): String? {
        val element = obj["tag_id"] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Strips a ```json fence Claude sometimes adds despite the system prompt's "no markdown fences" instruction. */
    private fun extractJsonObject(text: String): JsonObject? {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    private companion object {
        const val TAG = "AnthropicTagScorer"

        // Bead asn-k7i cause B: every ~5s (was 25s) -- 400 max_tokens on Haiku
        // still costs pennies even at this cadence (see README's cost note),
        // and this is the sole cadence knob TagCoordinator's gate reads via
        // TagScorer.minIntervalMs.
        const val DEFAULT_MIN_INTERVAL_MS = 5_000L

        /** Bead vn-edu.47: byte-identical to this class's pre-vn-edu.47 prompt -- the no-vault free-form path. */
        val LEGACY_SYSTEM_PROMPT = """
            You identify the MAJOR topics of an ongoing spoken conversation from a
            rolling transcript excerpt. Respond with ONLY strict JSON, no prose, no
            markdown fences, matching exactly this shape:
            {"tags":[{"tag":"<short topic label, 1-4 words>","confidence":<0.0-1.0>}]}
            Return at most 5 tags, most confident first. Confidence reflects how
            central and clearly-established the topic is in the excerpt, not how
            recently it was mentioned. Only include real subject-matter topics --
            never filler, stray words, or the speaker's name.
        """.trimIndent()

        /** Bead vn-edu.47: the tree-anchored prompt, used whenever a vault tag tree is available. */
        val TREE_SYSTEM_PROMPT = """
            You identify the MAJOR topics of an ongoing spoken conversation from a
            rolling transcript excerpt, anchored against the user's existing tag
            tree (a hierarchy of nouns naming their own recurring subjects -- e.g.
            "kitchen-remodel", "marathon-training"). Respond with ONLY strict JSON,
            no prose, no markdown fences, matching exactly this shape:
            {"tags":[{"tag":"<leaf name>","tag_id":"<id, or null>","confidence":<0.0-1.0>,"is_proposal":<true or false>}]}

            For each topic:
            - If it clearly matches an existing tree node -- by name, path, or
              description, or as a specific instance of one -- set "tag" to that
              node's exact leaf name and "tag_id" to that node's id; "is_proposal"
              is false.
            - Only when a topic clearly fits NO existing node, not even loosely,
              propose a brand-new noun-phrase tag: "tag_id" is null and
              "is_proposal" is true. This should be rare -- prefer an existing
              broader node over inventing a near-duplicate. Return AT MOST ONE
              proposal across the whole response, the single strongest case for
              a genuinely uncovered topic, even if more than one might qualify.

            Return at most 5 tags total, most confident first. Confidence reflects
            how central and clearly-established the topic is, not how recently it
            was mentioned. Never include filler, stray words, or the speaker's
            name. Tags are nouns.
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
