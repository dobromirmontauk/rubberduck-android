package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Failing-first repro for bead asn-76m: the user reported (2026-08-06,
 * on-device) that tapping a single tag chip to approve it made every other
 * tag jump to a new position. Root cause: [ThoughtCloud] used to assign each
 * word's fixed [ ][ThoughtCloud] `CloudSlot` by its *index* in the
 * `topWords`/`candidateWords` list (`forEachIndexed`). But approving a chip
 * re-ranks [com.montauk.voicecapture.tags.TagChipRail.chips]'s merged output
 * (approved/user chips move to the front, ahead of whatever suggested chips
 * remain) -- so a completely untouched word's list *index* shifts even
 * though nothing about that word itself changed, and the old index-based
 * slot lookup moved it to a different [CloudSlot] as a result.
 *
 * This test never touches [com.montauk.voicecapture.tags.TagChipRail]
 * directly -- it drives [ThoughtCloud] the same way
 * [com.montauk.voicecapture.ui.RecordingScreen] does (a plain
 * `List<ThoughtCloudWord>` passed in as state) and reproduces exactly the
 * reordering [TagChipRail.chips] would produce on approval: the tapped word
 * moves to the front of the list, the rest keep their relative order. Before
 * the fix (index-based slot assignment) this made "alpha" -- never tapped --
 * jump from [ThoughtCloud]'s slot 0 (top-left) to slot 1 (top-right); after
 * the fix (key-based [rememberStableSlotAssignment]) alpha's slot is
 * unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThoughtCloudPositionStabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val alpha = ThoughtCloudWord("alpha", 0.9, TagWordStatus.PROPOSED)
    private val beta = ThoughtCloudWord("beta", 0.85, TagWordStatus.PROPOSED)
    private val gamma = ThoughtCloudWord("gamma", 0.8, TagWordStatus.EXISTING)

    /**
     * Mirrors [com.montauk.voicecapture.tags.TagChipRail.chips]'s merge
     * order on approval: the just-approved chip becomes a USER chip and
     * sorts to the front, ahead of the remaining suggested chips, which keep
     * their existing relative order.
     */
    private fun approvedReorder(words: List<ThoughtCloudWord>, tapped: ThoughtCloudWord): List<ThoughtCloudWord> {
        val approved = tapped.copy(status = TagWordStatus.APPROVED)
        return listOf(approved) + words.filter { it.text != tapped.text }
    }

    @Test
    fun `approving one chip does not move any other chip's slot`() {
        composeTestRule.setContent {
            var words by remember { mutableStateOf(listOf(alpha, beta, gamma)) }
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ThoughtCloud(
                        words = words,
                        reducedMotion = true, // no drift/shimmer jitter to settle
                        onApprove = { tapped -> words = approvedReorder(words, tapped) },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val alphaBefore = composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "alpha").fetchSemanticsNode().boundsInRoot
        val gammaBefore = composeTestRule.onNodeWithTag(EXISTING_WORD_TEST_TAG_PREFIX + "gamma").fetchSemanticsNode().boundsInRoot
        val betaBeforeTop = composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "beta").fetchSemanticsNode().boundsInRoot.top

        // Tap "beta" -- NOT "alpha" or "gamma" -- to approve it. In
        // TagChipRail's real merge order this moves beta from list index 1
        // to index 0, shifting alpha from index 0 to index 1.
        composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "beta").performClick()
        composeTestRule.waitForIdle()

        val alphaAfter = composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "alpha").fetchSemanticsNode().boundsInRoot
        val gammaAfter = composeTestRule.onNodeWithTag(EXISTING_WORD_TEST_TAG_PREFIX + "gamma").fetchSemanticsNode().boundsInRoot
        // beta's own status flips PROPOSED -> APPROVED, so its test tag
        // changes prefix -- look it up under its new, approved tag.
        val betaAfterTop = composeTestRule.onNodeWithTag(APPROVED_WORD_TEST_TAG_PREFIX + "beta").fetchSemanticsNode().boundsInRoot.top

        // alpha and gamma were never tapped -- their whole bounds (position
        // AND size) must be untouched by beta's approval.
        assertEquals("alpha (untapped) must keep its exact position", alphaBefore, alphaAfter)
        assertEquals("gamma (untapped) must keep its exact position", gammaBefore, gammaAfter)
        // beta itself only changes color/label (PROPOSED -> APPROVED) in
        // place -- its vertical slot must not change even though it moved to
        // the front of the underlying chip list.
        assertEquals("the tapped chip itself must not jump to a different slot", betaBeforeTop, betaAfterTop)
    }
}
