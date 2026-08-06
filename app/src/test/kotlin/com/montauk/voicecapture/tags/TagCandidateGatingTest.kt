package com.montauk.voicecapture.tags

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bead vn-edu.47's "at most one clearly-marked proposal" backstop. */
class TagCandidateGatingTest {

    @Test
    fun `no proposals -- input returned unchanged`() {
        val candidates = listOf(TagCandidate("home", 0.9, tagId = "t_home"), TagCandidate("mashgin", 0.8, tagId = "t_mashgin"))

        assertEquals(candidates, TagCandidateGating.enforceAtMostOneProposal(candidates))
    }

    @Test
    fun `exactly one proposal -- input returned unchanged`() {
        val candidates = listOf(TagCandidate("home", 0.9, tagId = "t_home"), TagCandidate("gardening", 0.6, isProposal = true))

        assertEquals(candidates, TagCandidateGating.enforceAtMostOneProposal(candidates))
    }

    @Test
    fun `two proposals -- only the higher-confidence one survives, the other is dropped entirely`() {
        val weaker = TagCandidate("gardening", 0.5, isProposal = true)
        val stronger = TagCandidate("beekeeping", 0.7, isProposal = true)
        val candidates = listOf(weaker, stronger)

        val result = TagCandidateGating.enforceAtMostOneProposal(candidates)

        assertEquals(listOf(stronger), result)
    }

    @Test
    fun `three proposals -- only the single highest-confidence one survives`() {
        val candidates = listOf(
            TagCandidate("a", 0.4, isProposal = true),
            TagCandidate("b", 0.9, isProposal = true),
            TagCandidate("c", 0.6, isProposal = true),
        )

        val result = TagCandidateGating.enforceAtMostOneProposal(candidates)

        assertEquals(listOf(TagCandidate("b", 0.9, isProposal = true)), result)
    }

    @Test
    fun `non-proposal candidates are never dropped, regardless of how many proposals are present`() {
        val matched1 = TagCandidate("home", 0.95, tagId = "t_home")
        val weakProposal = TagCandidate("a", 0.5, isProposal = true)
        val matched2 = TagCandidate("mashgin", 0.85, tagId = "t_mashgin")
        val strongProposal = TagCandidate("b", 0.9, isProposal = true)

        val result = TagCandidateGating.enforceAtMostOneProposal(listOf(matched1, weakProposal, matched2, strongProposal))

        assertEquals(listOf(matched1, matched2, strongProposal), result)
    }

    @Test
    fun `a tie in confidence keeps exactly one proposal (the first at that confidence), never zero or two`() {
        val candidates = listOf(TagCandidate("a", 0.6, isProposal = true), TagCandidate("b", 0.6, isProposal = true))

        val result = TagCandidateGating.enforceAtMostOneProposal(candidates)

        assertEquals(1, result.count { it.isProposal })
        assertEquals(1, result.size)
    }

    @Test
    fun `empty input returns empty output`() {
        assertEquals(emptyList<TagCandidate>(), TagCandidateGating.enforceAtMostOneProposal(emptyList()))
    }
}
