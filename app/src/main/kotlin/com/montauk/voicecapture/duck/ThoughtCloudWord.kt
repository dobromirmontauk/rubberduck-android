package com.montauk.voicecapture.duck

import com.montauk.voicecapture.tags.DisplayedTag

/**
 * Color axis for a thought-cloud word (bead asn-0jk, design-board section 5)
 * -- confidence drives the word's SIZE separately (see [ThoughtCloudWords.fromDisplayedTags]);
 * this is purely status:
 *  - [EXISTING] (BLUE): a tree-matched tag already in the vault's `tags.yaml`.
 *  - [PROPOSED] (PURPLE, "new?" affix): a not-yet-in-the-tree tag, tappable
 *    to approve.
 *  - [APPROVED] (GREEN): a [PROPOSED] word the user has tapped -- will be
 *    created in the repo.
 *  - [CANDIDATE] (muted WHITE): outside the current top set, not yet
 *    confident enough to be either of the above.
 */
enum class TagWordStatus { EXISTING, PROPOSED, APPROVED, CANDIDATE }

/** One word in the duck's thought cloud: [text] + [confidence] (size axis) + [status] (color axis). */
data class ThoughtCloudWord(
    val text: String,
    val confidence: Double,
    val status: TagWordStatus,
)

/**
 * Adapts today's [DisplayedTag] stream (from [com.montauk.voicecapture.service.TagsStateHolder])
 * into the thought cloud's word list. Deliberately a free function over a
 * plain list + a set of approved keys, not tied to any StateFlow shape, so
 * it binds equally well to today's tracker output or a future richer
 * tag-rail state (asn-45m's [com.montauk.voicecapture.tags.TagRailChip]
 * already anticipates this exact word cloud in its own KDoc, but doesn't
 * yet carry the tree-match-vs-proposal-vs-approved distinction asn-0jk's
 * color spec needs -- this adapter is the seam to swap sources at later,
 * not the source of truth itself).
 */
object ThoughtCloudWords {
    /** Confident enough to be either EXISTING/blue or PROPOSED/purple/APPROVED-eligible. */
    const val MAX_TOP = 3

    /** Beyond the top set -- muted candidates only. */
    const val MAX_CANDIDATES = 3

    val EMPTY: List<ThoughtCloudWord> = emptyList()

    /**
     * [tags] is assumed already rank-ordered (as [com.montauk.voicecapture.tags.TagTracker]
     * returns it) -- the first [MAX_TOP] become EXISTING/PROPOSED/APPROVED
     * words, the next [MAX_CANDIDATES] become muted CANDIDATE words. [approvedKeys]
     * is normalized the same way [com.montauk.voicecapture.service.TagApprovalStateHolder]
     * normalizes its own keys.
     */
    fun fromDisplayedTags(tags: List<DisplayedTag>, approvedKeys: Set<String>): List<ThoughtCloudWord> {
        val top = tags.take(MAX_TOP).map { tag ->
            val status = when {
                normalize(tag.tag) in approvedKeys -> TagWordStatus.APPROVED
                tag.tagId != null -> TagWordStatus.EXISTING
                else -> TagWordStatus.PROPOSED
            }
            ThoughtCloudWord(tag.tag, tag.confidence, status)
        }
        val candidates = tags.drop(MAX_TOP).take(MAX_CANDIDATES).map { tag ->
            ThoughtCloudWord(tag.tag, tag.confidence, TagWordStatus.CANDIDATE)
        }
        return top + candidates
    }

    private fun normalize(tag: String): String = tag.trim().lowercase()
}
