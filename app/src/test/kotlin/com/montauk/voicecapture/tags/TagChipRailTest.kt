package com.montauk.voicecapture.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagChipRailTest {

    private fun tag(tag: String, tagId: String? = null, confidence: Double = 0.9, rank: Int = 1) =
        DisplayedTag(tag, confidence, rank, TagTier.forConfidence(confidence), tagId = tagId)

    @Test
    fun `a fresh rail with no suggestions and no user edits is empty`() {
        val rail = TagChipRail()
        assertTrue(rail.chips().isEmpty())
        assertNull(rail.primary())
    }

    @Test
    fun `onSuggested populates the rail with outlined SUGGESTED chips in rank order`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("marathon training", rank = 1), tag("nutrition", rank = 2)))

        val chips = rail.chips()
        assertEquals(listOf("marathon training", "nutrition"), chips.map { it.tag })
        assertTrue(chips.all { it.source == RailChipSource.SUGGESTED })
    }

    @Test
    fun `a SUGGESTED chip carries the scorer's confidence through -- for a word-cloud view to size or color by`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("marathon training", confidence = 0.87)))

        assertEquals(0.87, rail.chips().single().confidence)
    }

    @Test
    fun `a USER chip's confidence is always null -- a user pick has no scorer confidence`() {
        val rail = TagChipRail()
        rail.onAdd("kitchen remodel")

        assertNull(rail.chips().single().confidence)
    }

    @Test
    fun `onAdd inserts a filled USER chip ahead of the suggested chips`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("marathon training")))
        val changed = rail.onAdd("kitchen remodel", tagId = "t_kitchen")

        assertTrue(changed)
        val chips = rail.chips()
        assertEquals(listOf("kitchen remodel", "marathon training"), chips.map { it.tag })
        assertEquals(RailChipSource.USER, chips[0].source)
        assertEquals("t_kitchen", chips[0].tagId)
        assertEquals(RailChipSource.SUGGESTED, chips[1].source)
    }

    @Test
    fun `onAdd is a no-op (returns false, no duplicate) for a tag already added, case- and whitespace-insensitively`() {
        val rail = TagChipRail()
        rail.onAdd("Kitchen Remodel")

        val changed = rail.onAdd("  kitchen remodel  ")

        assertFalse(changed)
        assertEquals(1, rail.chips().size)
    }

    @Test
    fun `onAdd with blank text is a no-op`() {
        val rail = TagChipRail()
        assertFalse(rail.onAdd("   "))
        assertTrue(rail.chips().isEmpty())
    }

    @Test
    fun `onRemove drops a user chip outright and returns true`() {
        val rail = TagChipRail()
        rail.onAdd("kitchen remodel")

        val changed = rail.onRemove("kitchen remodel")

        assertTrue(changed)
        assertTrue(rail.chips().isEmpty())
    }

    @Test
    fun `onRemove of something not currently showing returns false`() {
        val rail = TagChipRail()
        assertFalse(rail.onRemove("nonexistent"))
    }

    @Test
    fun `onRemove is a no-op the second time for the same already-removed tag`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("marathon training")))
        assertTrue(rail.onRemove("marathon training"))

        val secondRemove = rail.onRemove("marathon training")

        assertFalse(secondRemove)
    }

    // --- Sticky removal (bead asn-45m core requirement) ---

    @Test
    fun `a removed suggested tag does not resurrect when onSuggested re-feeds the exact same tag`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("gardening")))
        rail.onRemove("gardening")

        // Scorer keeps re-suggesting the same tag on a later cycle.
        rail.onSuggested(listOf(tag("gardening"), tag("budget")))

        assertEquals(listOf("budget"), rail.chips().map { it.tag })
    }

    @Test
    fun `sticky removal is keyed case- and whitespace-insensitively, matching onAdd's own normalization`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("Gardening")))
        rail.onRemove("gardening")

        rail.onSuggested(listOf(tag("  GARDENING  ")))

        assertTrue(rail.chips().isEmpty())
    }

    @Test
    fun `an explicit re-add through onAdd clears the earlier sticky removal`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("gardening")))
        rail.onRemove("gardening")

        val changed = rail.onAdd("gardening")

        assertTrue(changed)
        val chips = rail.chips()
        assertEquals(listOf("gardening"), chips.map { it.tag })
        assertEquals(RailChipSource.USER, chips[0].source)
        // Confirms the removal really was cleared, not just shadowed by the
        // user chip: a fresh suggestion for the same tag must not duplicate it.
        rail.onSuggested(listOf(tag("gardening")))
        assertEquals(1, rail.chips().size)
    }

    // --- Swap ---

    @Test
    fun `onSwap atomically replaces a suggested chip with a filled USER chip for the new tag`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("gardening", tagId = "t_garden")))

        val changed = rail.onSwap("gardening", "landscaping", newTagId = "t_landscape")

        assertTrue(changed)
        val chips = rail.chips()
        assertEquals(listOf("landscaping"), chips.map { it.tag })
        assertEquals(RailChipSource.USER, chips[0].source)
        assertEquals("t_landscape", chips[0].tagId)
    }

    @Test
    fun `onSwap never resurrects the old tag on a later onSuggested re-feed`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("gardening")))
        rail.onSwap("gardening", "landscaping")

        rail.onSuggested(listOf(tag("gardening")))

        assertEquals(listOf("landscaping"), rail.chips().map { it.tag })
    }

    @Test
    fun `re-picking the SAME tag through onSwap confirms it into a filled USER chip`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("gardening", tagId = "t_garden")))

        val changed = rail.onSwap("gardening", "gardening", newTagId = "t_garden")

        assertTrue(changed)
        val chips = rail.chips()
        assertEquals(1, chips.size)
        assertEquals(RailChipSource.USER, chips[0].source)
    }

    // --- Primary-tag selection (drives the filing-destination ribbon) ---

    @Test
    fun `primary is null when the rail is empty`() {
        assertNull(TagChipRail().primary())
    }

    @Test
    fun `primary prefers a user chip over any suggested chip, regardless of suggested rank`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("marathon training", rank = 1, confidence = 0.95)))
        rail.onAdd("kitchen remodel")

        assertEquals("kitchen remodel", rail.primary()?.tag)
    }

    @Test
    fun `primary falls back to the top-ranked suggested chip when there are no user chips`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(tag("nutrition", rank = 2), tag("marathon training", rank = 1)))

        assertEquals("marathon training", rail.primary()?.tag)
    }

    @Test
    fun `primary is the first-added user chip when multiple user chips exist`() {
        val rail = TagChipRail()
        rail.onAdd("first")
        rail.onAdd("second")

        assertEquals("first", rail.primary()?.tag)
    }
}
