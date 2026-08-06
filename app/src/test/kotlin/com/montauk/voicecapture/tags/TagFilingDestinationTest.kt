package com.montauk.voicecapture.tags

import org.junit.Assert.assertEquals
import org.junit.Test

class TagFilingDestinationTest {

    private fun tree(): TagTree = TagTree(
        listOf(
            TagTreeNode(id = "t_work", name = "work", parentId = null, description = "Work."),
            TagTreeNode(id = "t_mashgin", name = "mashgin", parentId = "t_work", description = "Mashgin."),
        ),
    )

    @Test
    fun `an empty rail (null primary) falls back to the inbox destination`() {
        val destination = TagFilingDestination.destinationFor(null, TagTree.EMPTY)

        assertEquals(TagFilingDestination.NO_TAGS_DESTINATION, destination)
        assertEquals("notes/inbox.md", destination)
    }

    @Test
    fun `a tree-matched chip derives the destination from the tag tree's full ancestor path`() {
        val primary = TagRailChip("mashgin", tagId = "t_mashgin", source = RailChipSource.USER)

        val destination = TagFilingDestination.destinationFor(primary, tree())

        assertEquals("notes/work/mashgin.md", destination)
    }

    @Test
    fun `a free-form chip (no tagId) slugifies its own text instead of consulting the tree`() {
        val primary = TagRailChip("kitchen remodel", tagId = null, source = RailChipSource.USER)

        val destination = TagFilingDestination.destinationFor(primary, tree())

        assertEquals("notes/kitchen-remodel.md", destination)
    }

    @Test
    fun `a dangling tagId that resolves to no path falls back to slugifying the chip text`() {
        val primary = TagRailChip("some tag", tagId = "t_does_not_exist", source = RailChipSource.SUGGESTED)

        val destination = TagFilingDestination.destinationFor(primary, tree())

        assertEquals("notes/some-tag.md", destination)
    }

    @Test
    fun `slugify collapses punctuation and repeated separators, trimming leading-trailing dashes`() {
        val primary = TagRailChip("  Budget & Timeline!!  ", tagId = null, source = RailChipSource.USER)

        val destination = TagFilingDestination.destinationFor(primary, TagTree.EMPTY)

        assertEquals("notes/budget-timeline.md", destination)
    }
}
