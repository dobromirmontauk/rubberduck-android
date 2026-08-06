package com.montauk.voicecapture.tags

/**
 * Bead vn-edu.47: when scored against a vault's tag tree, a scorer should
 * match speech to EXISTING nodes first and propose a genuinely new
 * (not-in-the-tree) tag only when nothing fits -- mirroring the organize
 * skill's "confident new subtopic" bar, i.e. rare and clearly marked, never
 * a grab-bag of guesses. [enforceAtMostOneProposal] is the defensive
 * backstop for that rule: even if a scorer's raw output somehow carries more
 * than one [TagCandidate.isProposal] entry (a model ignoring its own
 * instructions, say), at most one survives.
 */
object TagCandidateGating {
    /**
     * Keeps every non-proposal candidate untouched. Of the proposal-flagged
     * candidates, keeps only the highest-confidence one and drops the rest
     * entirely -- not merely un-flagging them, since a demoted "proposal
     * that quietly stopped being a proposal" would misrepresent itself as a
     * tree match with no [TagCandidate.tagId], which is worse than simply
     * not showing it.
     */
    fun enforceAtMostOneProposal(candidates: List<TagCandidate>): List<TagCandidate> {
        val proposals = candidates.withIndex().filter { it.value.isProposal }
        if (proposals.size <= 1) return candidates

        val keepIndex = proposals.maxByOrNull { it.value.confidence }!!.index
        return candidates.filterIndexed { index, candidate -> !candidate.isProposal || index == keepIndex }
    }
}
