package com.montauk.voicecapture.tags

import org.junit.Assert.assertEquals
import org.junit.Test

/** UI state mapping: confidence -> chip size tier, consumed by `RecordingScreen.TagChip`. */
class TagTierTest {

    @Test
    fun `high confidence maps to PRIMARY`() {
        assertEquals(TagTier.PRIMARY, TagTier.forConfidence(0.75))
        assertEquals(TagTier.PRIMARY, TagTier.forConfidence(0.9))
        assertEquals(TagTier.PRIMARY, TagTier.forConfidence(1.0))
    }

    @Test
    fun `mid confidence maps to SECONDARY`() {
        assertEquals(TagTier.SECONDARY, TagTier.forConfidence(0.5))
        assertEquals(TagTier.SECONDARY, TagTier.forConfidence(0.6))
        assertEquals(TagTier.SECONDARY, TagTier.forConfidence(0.74))
    }

    @Test
    fun `low confidence maps to TERTIARY`() {
        assertEquals(TagTier.TERTIARY, TagTier.forConfidence(0.0))
        assertEquals(TagTier.TERTIARY, TagTier.forConfidence(0.3))
        assertEquals(TagTier.TERTIARY, TagTier.forConfidence(0.49))
    }

    @Test
    fun `boundaries are inclusive on the high side of each bucket`() {
        // 0.75 and 0.5 are exactly the PRIMARY/SECONDARY cutoffs -- confirms
        // which side of the boundary each belongs to, since off-by-one here
        // would silently mis-size chips right at common confidence values.
        assertEquals(TagTier.PRIMARY, TagTier.forConfidence(0.75))
        assertEquals(TagTier.SECONDARY, TagTier.forConfidence(0.749999))
        assertEquals(TagTier.SECONDARY, TagTier.forConfidence(0.5))
        assertEquals(TagTier.TERTIARY, TagTier.forConfidence(0.499999))
    }
}
