package com.montauk.voicecapture.duck

import com.montauk.voicecapture.tags.DisplayedTag
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
}
