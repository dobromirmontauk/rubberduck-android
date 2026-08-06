package com.montauk.voicecapture.duck

import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.RailChipSource
import com.montauk.voicecapture.tags.TagRailChip
import com.montauk.voicecapture.tags.TagTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ThoughtCloudWordsTest {

    @Test
    fun `a tree-matched tag is EXISTING`() {
        val tags = listOf(DisplayedTag("kitchen-remodel", 0.9, rank = 1, tier = TagTier.PRIMARY, tagId = "t_kitchen"))
        val words = ThoughtCloudWords.fromDisplayedTags(tags, approvedKeys = emptySet())
        assertEquals(listOf(TagWordStatus.EXISTING), words.map { it.status })
    }

    @Test
    fun `a tag with no tree match is PROPOSED, regardless of the isProposal flag`() {
        val proposalFlagged = listOf(DisplayedTag("gardening", 0.6, rank = 1, tier = TagTier.SECONDARY, isProposal = true))
        val legacyFreeForm = listOf(DisplayedTag("marathon training", 0.9, rank = 1, tier = TagTier.PRIMARY))

        assertEquals(listOf(TagWordStatus.PROPOSED), ThoughtCloudWords.fromDisplayedTags(proposalFlagged, emptySet()).map { it.status })
        assertEquals(listOf(TagWordStatus.PROPOSED), ThoughtCloudWords.fromDisplayedTags(legacyFreeForm, emptySet()).map { it.status })
    }

    @Test
    fun `a PROPOSED tag the user has approved becomes APPROVED, even ahead of EXISTING`() {
        val tags = listOf(DisplayedTag("kitchen-remodel", 0.9, rank = 1, tier = TagTier.PRIMARY))
        val words = ThoughtCloudWords.fromDisplayedTags(tags, approvedKeys = setOf("kitchen-remodel"))
        assertEquals(listOf(TagWordStatus.APPROVED), words.map { it.status })
    }

    @Test
    fun `approval matching is case- and whitespace-insensitive, same normalization as the tracker`() {
        val tags = listOf(DisplayedTag("Kitchen Remodel", 0.9, rank = 1, tier = TagTier.PRIMARY))
        val words = ThoughtCloudWords.fromDisplayedTags(tags, approvedKeys = setOf("kitchen remodel"))
        assertEquals(listOf(TagWordStatus.APPROVED), words.map { it.status })
    }

    @Test
    fun `only the top MAX_TOP tags can be EXISTING, PROPOSED, or APPROVED -- the rest are CANDIDATE`() {
        val tags = (1..6).map { i -> DisplayedTag("tag-$i", confidence = 1.0 - i * 0.1, rank = i, tier = TagTier.PRIMARY) }
        val words = ThoughtCloudWords.fromDisplayedTags(tags, approvedKeys = emptySet())

        val topStatuses = words.take(ThoughtCloudWords.MAX_TOP).map { it.status }
        val restStatuses = words.drop(ThoughtCloudWords.MAX_TOP).map { it.status }

        assertEquals(List(ThoughtCloudWords.MAX_TOP) { TagWordStatus.PROPOSED }, topStatuses)
        assertEquals(List(restStatuses.size) { TagWordStatus.CANDIDATE }, restStatuses)
    }

    @Test
    fun `candidates are capped at MAX_CANDIDATES`() {
        val tags = (1..10).map { i -> DisplayedTag("tag-$i", confidence = 1.0 - i * 0.05, rank = i, tier = TagTier.PRIMARY) }
        val words = ThoughtCloudWords.fromDisplayedTags(tags, approvedKeys = emptySet())
        assertEquals(ThoughtCloudWords.MAX_TOP + ThoughtCloudWords.MAX_CANDIDATES, words.size)
    }

    @Test
    fun `empty input yields an empty list`() {
        assertEquals(ThoughtCloudWords.EMPTY, ThoughtCloudWords.fromDisplayedTags(emptyList(), emptySet()))
    }

    @Test
    fun `confidence and text pass through unchanged`() {
        val tags = listOf(DisplayedTag("budget", 0.73, rank = 1, tier = TagTier.PRIMARY, tagId = "t_budget"))
        val word = ThoughtCloudWords.fromDisplayedTags(tags, emptySet()).single()
        assertEquals("budget", word.text)
        assertEquals(0.73, word.confidence, 1e-9)
    }

    // --- fromTagRailChips (bead asn-45m's TagRailChip rail, superseding
    // DisplayedTag+approvedKeys as this word cloud's real-world source).

    @Test
    fun `a tree-matched rail chip is EXISTING regardless of source`() {
        val chips = listOf(TagRailChip("kitchen-remodel", tagId = "t_kitchen", source = RailChipSource.SUGGESTED))
        val words = ThoughtCloudWords.fromTagRailChips(chips)
        assertEquals(listOf(TagWordStatus.EXISTING), words.map { it.status })
    }

    @Test
    fun `an unapproved (still SUGGESTED) proposal is PROPOSED`() {
        val chips = listOf(TagRailChip("gardening", tagId = null, source = RailChipSource.SUGGESTED))
        val words = ThoughtCloudWords.fromTagRailChips(chips)
        assertEquals(listOf(TagWordStatus.PROPOSED), words.map { it.status })
    }

    @Test
    fun `a USER chip with no tagId is an approved proposal -- APPROVED, not PROPOSED`() {
        val chips = listOf(TagRailChip("kitchen remodel", tagId = null, source = RailChipSource.USER))
        val words = ThoughtCloudWords.fromTagRailChips(chips)
        assertEquals(listOf(TagWordStatus.APPROVED), words.map { it.status })
    }

    @Test
    fun `only the top MAX_TOP chips can be EXISTING, PROPOSED, or APPROVED -- the rest are CANDIDATE`() {
        val chips = (1..6).map { i -> TagRailChip("tag-$i", tagId = null, source = RailChipSource.SUGGESTED, confidence = 1.0 - i * 0.1) }
        val words = ThoughtCloudWords.fromTagRailChips(chips)

        val topStatuses = words.take(ThoughtCloudWords.MAX_TOP).map { it.status }
        val restStatuses = words.drop(ThoughtCloudWords.MAX_TOP).map { it.status }

        assertEquals(List(ThoughtCloudWords.MAX_TOP) { TagWordStatus.PROPOSED }, topStatuses)
        assertEquals(List(restStatuses.size) { TagWordStatus.CANDIDATE }, restStatuses)
    }

    @Test
    fun `a USER chip's null confidence sizes as fully confident, not faded`() {
        val chips = listOf(TagRailChip("kitchen remodel", tagId = null, source = RailChipSource.USER))
        val word = ThoughtCloudWords.fromTagRailChips(chips).single()
        assertEquals(1.0, word.confidence, 1e-9)
    }

    @Test
    fun `a SUGGESTED chip's real confidence passes through unchanged`() {
        val chips = listOf(TagRailChip("budget", tagId = "t_budget", source = RailChipSource.SUGGESTED, confidence = 0.73))
        val word = ThoughtCloudWords.fromTagRailChips(chips).single()
        assertEquals(0.73, word.confidence, 1e-9)
    }

    @Test
    fun `empty rail yields an empty list`() {
        assertEquals(ThoughtCloudWords.EMPTY, ThoughtCloudWords.fromTagRailChips(emptyList()))
    }
}
