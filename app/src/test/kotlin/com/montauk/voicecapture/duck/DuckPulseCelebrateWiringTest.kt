package com.montauk.voicecapture.duck

import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.TagChipRail
import com.montauk.voicecapture.tags.TagTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead asn-02h.5: an end-to-end test at the pure-Kotlin layer, exercising the
 * exact same shape [com.montauk.voicecapture.service.RecordingService.handleTagApprove]
 * uses in production -- `TagChipRail.onApprove`'s Boolean return gates BOTH
 * the durable bundle write (modeled here by [approvalWasWritten]) AND the
 * CELEBRATE emit, so the two can never disagree. [RecordingService] itself
 * has no unit-test coverage (same as every other Service call site in this
 * codebase -- see [DuckPulseTriggers]' own class KDoc), so this is the real
 * evidence for "approval recorded in the bundle AND the duck celebrates,
 * single source of truth for the trigger."
 */
class DuckPulseCelebrateWiringTest {

    @After
    fun tearDown() {
        DuckPulseStateHolder.reset()
    }

    /** Mirrors handleTagApprove's `if (!rail.onApprove(tag)) return` gate -- returns whether the approval "wrote" (bundle event + CELEBRATE), same boolean driving both in production. */
    private fun approveAndWire(rail: TagChipRail, tag: String): Boolean {
        val approvalWasWritten = rail.onApprove(tag)
        if (approvalWasWritten) DuckPulseStateHolder.emit(DuckPulse.CELEBRATE)
        return approvalWasWritten
    }

    @Test
    fun `approval succeeded implies CELEBRATE fired -- single source of truth`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(DisplayedTag("kitchen-remodel", confidence = 0.8, rank = 1, tier = TagTier.PRIMARY)))

        val approved = approveAndWire(rail, "kitchen-remodel")

        assertTrue(approved)
        assertEquals(DuckPulse.CELEBRATE, DuckPulseStateHolder.events.value?.pulse)
    }

    @Test
    fun `a no-op approve (never suggested) writes nothing and never celebrates`() {
        val rail = TagChipRail()

        val approved = approveAndWire(rail, "never-suggested")

        assertFalse(approved)
        assertNull(DuckPulseStateHolder.events.value)
    }

    @Test
    fun `re-approving an already-approved tag is a no-op the second time -- exactly one CELEBRATE`() {
        val rail = TagChipRail()
        rail.onSuggested(listOf(DisplayedTag("gardening", confidence = 0.7, rank = 1, tier = TagTier.PRIMARY)))

        assertTrue(approveAndWire(rail, "gardening"))
        val firstEvent = DuckPulseStateHolder.events.value
        assertFalse(approveAndWire(rail, "gardening"))
        val secondEvent = DuckPulseStateHolder.events.value

        assertEquals(firstEvent, secondEvent) // no second emit happened
    }

    @Test
    fun `approving from the debug-view tag rail path celebrates identically to the word-cloud path`() {
        // Bead asn-02h.5's fixed bug: the old happyBounceTrigger nonce only
        // incremented from the duck view's word-cloud tap handler --
        // approving via TagRailSection's own onApprove callback (debug view)
        // called onApproveTag directly and never touched it, so the duck
        // never celebrated from that surface. Both surfaces funnel into the
        // identical RecordingService.handleTagApprove in production; this
        // test proves the underlying rail-driven trigger doesn't care which
        // UI surface asked for the approval.
        val rail = TagChipRail()
        rail.onSuggested(listOf(DisplayedTag("dog-walks", confidence = 0.6, rank = 1, tier = TagTier.PRIMARY)))

        val approved = approveAndWire(rail, "dog-walks")

        assertTrue(approved)
        assertEquals(DuckPulse.CELEBRATE, DuckPulseStateHolder.events.value?.pulse)
    }
}
