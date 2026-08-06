package com.montauk.voicecapture.summary

/**
 * Client-side backstop for [SummaryCoordinator]'s "hard stability contract"
 * (bead asn-evl): [AnthropicSummaryGenerator]'s system prompt already asks
 * the model to return [previous]'s bullets first, verbatim, and only append
 * genuinely new ones -- but a prompt is a request, not a guarantee, and a
 * model that "cleans up" or rewords a stable bullet it was told to leave
 * alone must never be allowed to change what's already on screen. This is
 * the actual enforcement: [previous] is always returned unchanged, no matter
 * what [proposed] did to that portion of its response -- only content past
 * [previous]'s length is ever treated as new.
 *
 * Deliberately does not attempt to detect "the model legitimately fixed a
 * factual error" vs. "the model gratuitously reworded a stable bullet" --
 * telling those apart from a text diff alone isn't reliable, and the cost of
 * occasionally missing a rare, real correction is far lower than the cost of
 * a summary that visibly rewrites itself out from under the user. If a
 * correction is ever needed, it has to come in as a new bullet.
 */
object AppendOnlyBulletMerge {
    /**
     * [previous] is returned verbatim as the prefix of the result, always --
     * [proposed]'s own version of that prefix (if any -- same index range)
     * is ignored entirely, matched or not. Anything [proposed] offers beyond
     * [previous]'s length is a candidate new bullet, kept only if it's
     * non-blank and isn't (case/whitespace-insensitively) a duplicate of a
     * bullet already in [previous] -- a model that re-emits a rewrite of an
     * existing bullet as if it were new must not get to sneak it in as a
     * second, duplicate entry either.
     */
    fun merge(previous: List<String>, proposed: List<String>): List<String> {
        if (proposed.size <= previous.size) return previous
        val existingNormalized = previous.map { normalize(it) }.toSet()
        val genuinelyNew = proposed.drop(previous.size)
            .filter { it.isNotBlank() && normalize(it) !in existingNormalized }
        return previous + genuinelyNew
    }

    private fun normalize(bullet: String): String = bullet.trim().lowercase()
}
