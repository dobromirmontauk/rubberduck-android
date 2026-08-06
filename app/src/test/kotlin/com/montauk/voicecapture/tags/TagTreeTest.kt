package com.montauk.voicecapture.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead vn-edu.47: [TagTreeParser] is a tolerant hand-rolled parser for
 * `tags.yaml`'s specific shape, not a general YAML parser -- these tests
 * exercise it against a real excerpt of voice-vault's actual `tags.yaml`
 * (comments, header, a run-on entry with no blank-line separator) so the
 * app-side parser is proven against the vault's real shape, not a
 * simplified stand-in.
 */
class TagTreeTest {

    private val realVaultExcerpt = """
        # Hierarchical tag tree for voice-vault.
        #
        # Rules (do not violate these; they are what makes tag_ids stable references):
        #   - `id` is PERMANENT. Once assigned, an id is never reused and never deleted.

        - id: t_01KZ8AQPFPTEJDX9A0X1GAZ21P
          name: work
          parent: null
          description: Professional life in general -- anything not specific to Mashgin.

        - id: t_01KZ8AQPFP71ENN6QR37Y5E9DC
          name: mashgin
          parent: t_01KZ8AQPFPTEJDX9A0X1GAZ21P
          description: Mashgin-specific work -- the job, not the industry.

        - id: t_01KZ8AQPFP3H1NEDS75SSJ4517
          name: home
          parent: null
          description: House projects, maintenance, household logistics.
        - id: t_01KZ8CKQ8Y60H8YQADG5RMNQ30
          name: kitchen-remodel
          parent: t_01KZ8AQPFP3H1NEDS75SSJ4517
          description: The kitchen remodel project -- contractors, bids, timeline, decisions.
    """.trimIndent()

    @Test
    fun `parses every entry from a real voice-vault excerpt, including a run-on entry with no blank-line separator`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)

        assertEquals(4, tree.nodes.size)
        assertEquals(listOf("work", "mashgin", "home", "kitchen-remodel"), tree.nodes.map { it.name })
    }

    @Test
    fun `header comments and blank lines are skipped, never parsed as entries`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)

        assertTrue(tree.nodes.none { it.name.startsWith("#") })
    }

    @Test
    fun `a null parent resolves to a root (parentId null), not the literal string 'null'`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)

        val work = tree.nodes.first { it.name == "work" }
        assertNull(work.parentId)
    }

    @Test
    fun `a real parent id is preserved verbatim`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)

        val mashgin = tree.nodes.first { it.name == "mashgin" }
        assertEquals("t_01KZ8AQPFPTEJDX9A0X1GAZ21P", mashgin.parentId)
    }

    @Test
    fun `path joins the ancestor chain root-first by name`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)
        val mashgin = tree.nodes.first { it.name == "mashgin" }

        assertEquals("work/mashgin", tree.path(mashgin.id))
    }

    @Test
    fun `path for a root node is just its own name`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)
        val home = tree.nodes.first { it.name == "home" }

        assertEquals("home", tree.path(home.id))
    }

    @Test
    fun `deprecated defaults to false when the key is absent`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)

        assertTrue(tree.nodes.none { it.deprecated })
    }

    @Test
    fun `a deprecated true node parses as deprecated and is excluded from activeNodes`() {
        val yaml = """
            - id: t_old
              name: old-project
              parent: null
              description: No longer active.
              deprecated: true
        """.trimIndent()

        val tree = TagTreeParser.parse(yaml)

        assertTrue(tree.node("t_old")!!.deprecated)
        assertTrue(tree.activeNodes().isEmpty())
    }

    @Test
    fun `activeNodes excludes only deprecated nodes, preserving file order for the rest`() {
        val yaml = """
            - id: t_a
              name: alpha
              parent: null
              description: A.
            - id: t_b
              name: bravo
              parent: null
              description: B.
              deprecated: true
            - id: t_c
              name: charlie
              parent: null
              description: C.
        """.trimIndent()

        val tree = TagTreeParser.parse(yaml)

        assertEquals(listOf("alpha", "charlie"), tree.activeNodes().map { it.name })
    }

    @Test
    fun `an entry missing a required id or name is dropped rather than failing the whole parse`() {
        val yaml = """
            - id: t_good
              name: good-one
              parent: null
              description: Fine.
            - name: missing-id
              parent: null
              description: No id key at all.
            - id: t_missing_name
              parent: null
              description: No name key at all.
        """.trimIndent()

        val tree = TagTreeParser.parse(yaml)

        assertEquals(listOf("good-one"), tree.nodes.map { it.name })
    }

    @Test
    fun `an unknown key is ignored rather than failing the parse`() {
        val yaml = """
            - id: t_good
              name: good-one
              parent: null
              description: Fine.
              some_future_field: whatever the vault adds next
        """.trimIndent()

        val tree = TagTreeParser.parse(yaml)

        assertEquals(1, tree.nodes.size)
        assertEquals("good-one", tree.nodes.single().name)
    }

    @Test
    fun `empty input parses to an empty tree, same as TagTree EMPTY`() {
        val tree = TagTreeParser.parse("")

        assertTrue(tree.isEmpty)
        assertEquals(0, tree.nodes.size)
    }

    @Test
    fun `TagTree EMPTY has no nodes and isEmpty is true`() {
        assertTrue(TagTree.EMPTY.isEmpty)
        assertTrue(TagTree.EMPTY.nodes.isEmpty())
    }

    @Test
    fun `a non-empty tree reports isEmpty false`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)

        assertFalse(tree.isEmpty)
    }

    @Test
    fun `node looks up by id, returning null for an unknown id`() {
        val tree = TagTreeParser.parse(realVaultExcerpt)
        val work = tree.nodes.first { it.name == "work" }

        assertEquals(work, tree.node(work.id))
        assertNull(tree.node("t_does_not_exist"))
    }

    @Test
    fun `a dangling parent id stops the path walk at that point rather than throwing`() {
        val yaml = """
            - id: t_child
              name: child
              parent: t_does_not_exist
              description: Orphaned.
        """.trimIndent()

        val tree = TagTreeParser.parse(yaml)

        assertEquals("child", tree.path("t_child"))
    }
}
