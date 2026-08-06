package com.montauk.voicecapture.duck

import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.TagRailChip
import com.montauk.voicecapture.tags.TagStatus

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

    /**
     * Adapts bead asn-45m's [TagRailChip] rail (the schema
     * [com.montauk.voicecapture.service.TagRailStateHolder] publishes) into
     * the thought cloud's word list -- the seam [fromDisplayedTags]'s own
     * KDoc anticipated, now that the tag rail's own color axis (asn-0jk)
     * carries the exact same status split this word cloud needs:
     * ```
     * status == EXISTING                   -> EXISTING (blue)
     * status == PROPOSED_NEW && !approved  -> PROPOSED (purple)
     * status == PROPOSED_NEW && approved   -> APPROVED (green)
     * ```
     * [chips] is assumed already in the rail's own display order (user
     * chips first, then suggested, per [com.montauk.voicecapture.tags.TagChipRail.chips]) --
     * same top/candidate split as [fromDisplayedTags]. [TagRailChip.confidence]
     * is null for every [com.montauk.voicecapture.tags.RailChipSource.USER]
     * chip (that field's own KDoc: "a user's own pick has no scorer
     * confidence, it's simply confirmed") -- sized as fully confident
     * ([MAX_CONFIDENCE]) rather than faded, since a user-added/approved word
     * should never read as tentative.
     */
    fun fromTagRailChips(chips: List<TagRailChip>): List<ThoughtCloudWord> {
        val top = chips.take(MAX_TOP).map { chip -> ThoughtCloudWord(chip.tag, chip.confidence ?: MAX_CONFIDENCE, chip.wordStatus) }
        val candidates = chips.drop(MAX_TOP).take(MAX_CANDIDATES).map { chip ->
            ThoughtCloudWord(chip.tag, chip.confidence ?: MAX_CONFIDENCE, TagWordStatus.CANDIDATE)
        }
        return top + candidates
    }

    /** Confidence assigned to a [TagRailChip] with no scorer confidence of its own -- see [fromTagRailChips]. */
    private const val MAX_CONFIDENCE = 1.0

    private fun normalize(tag: String): String = tag.trim().lowercase()
}

private val TagRailChip.wordStatus: TagWordStatus
    get() = when {
        status == TagStatus.EXISTING -> TagWordStatus.EXISTING
        approved -> TagWordStatus.APPROVED
        else -> TagWordStatus.PROPOSED
    }
