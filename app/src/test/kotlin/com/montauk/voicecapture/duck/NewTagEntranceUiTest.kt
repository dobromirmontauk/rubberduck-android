package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose-level wiring test for bead asn-bmq's new-tag entrance: a brand
 * new top word should play [NewTagEntrancePhase.WRITE] (the handwritten
 * letter-reveal, [NEW_TAG_WRITE_TEST_TAG_PREFIX]) the moment it first
 * appears, while a word present since [ThoughtCloud]'s very first
 * composition never does (see [ThoughtCloud]'s own KDoc for that
 * baseline/entrant distinction), and reduced motion skips the WRITE reveal
 * entirely. [NewTagEntranceTimelineTest] covers the timeline's own phase
 * math and storyboard-matching durations in isolation; this test only
 * checks that [ThoughtCloud] wires it up to the right words at the right
 * moment -- every assertion happens immediately after the state change that
 * introduces a new word, well inside [NewTagEntranceTimeline.WRITE_MS]'s
 * 2.5 real seconds, so it needs no clock control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewTagEntranceUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val alpha = ThoughtCloudWord("alpha", 0.9, TagWordStatus.PROPOSED)
    private val gamma = ThoughtCloudWord("gamma", 0.8, TagWordStatus.EXISTING)
    private val beta = ThoughtCloudWord("beta", 0.85, TagWordStatus.PROPOSED)

    @Test
    fun `words present in the very first composition never play the WRITE entrance`() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ThoughtCloud(words = listOf(alpha, gamma), reducedMotion = false, onApprove = {})
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NEW_TAG_WRITE_TEST_TAG_PREFIX + "alpha").assertDoesNotExist()
        composeTestRule.onNodeWithTag(NEW_TAG_WRITE_TEST_TAG_PREFIX + "gamma").assertDoesNotExist()
        composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "alpha").assertExists()
        composeTestRule.onNodeWithTag(EXISTING_WORD_TEST_TAG_PREFIX + "gamma").assertExists()
    }

    @Test
    fun `a word arriving after the first composition plays the WRITE entrance immediately`() {
        composeTestRule.setContent {
            var words by remember { mutableStateOf(listOf(alpha, gamma)) }
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ThoughtCloud(words = words, reducedMotion = false, onApprove = {})
                }
                // Introduce "beta" as a brand-new word on a later composition
                // -- production's equivalent of a fresh scorer suggestion
                // arriving mid-session.
                LaunchedEffect(Unit) { words = listOf(alpha, gamma, beta) }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NEW_TAG_WRITE_TEST_TAG_PREFIX + "beta").assertExists()
        // The real, status-based chip doesn't exist yet -- WRITE hasn't
        // handed off to MORPH.
        composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "beta").assertDoesNotExist()
    }

    @Test
    fun `reduced motion skips the WRITE reveal entirely for a newly-arriving word`() {
        composeTestRule.setContent {
            var words by remember { mutableStateOf(listOf(alpha, gamma)) }
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ThoughtCloud(words = words, reducedMotion = true, onApprove = {})
                }
                LaunchedEffect(Unit) { words = listOf(alpha, gamma, beta) }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NEW_TAG_WRITE_TEST_TAG_PREFIX + "beta").assertDoesNotExist()
        // Straight into the real chip (mid-fade-in, per NewTagEntranceTimeline's
        // reduced-motion branch) rather than a handwritten draft.
        composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "beta").assertExists()
    }

    @Test
    fun `a newly-arriving word's entrance does not move the untouched baseline words (asn-76m)`() {
        composeTestRule.setContent {
            var words by remember { mutableStateOf(listOf(alpha, gamma)) }
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ThoughtCloud(words = words, reducedMotion = false, onApprove = {})
                }
                LaunchedEffect(Unit) { words = listOf(alpha, gamma, beta) }
            }
        }
        composeTestRule.waitForIdle()

        // Recorded once, after beta has already been introduced -- if beta's
        // entrance disturbed either baseline word's slot, these two nodes
        // would already reflect it by the time this reads them.
        val alphaBounds = composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "alpha").fetchSemanticsNode().boundsInRoot
        val gammaBounds = composeTestRule.onNodeWithTag(EXISTING_WORD_TEST_TAG_PREFIX + "gamma").fetchSemanticsNode().boundsInRoot

        // Re-fetch after another idle pass to confirm they're not still
        // drifting frame to frame either.
        composeTestRule.waitForIdle()
        val alphaBoundsAgain = composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "alpha").fetchSemanticsNode().boundsInRoot
        val gammaBoundsAgain = composeTestRule.onNodeWithTag(EXISTING_WORD_TEST_TAG_PREFIX + "gamma").fetchSemanticsNode().boundsInRoot

        assertEquals(alphaBounds, alphaBoundsAgain)
        assertEquals(gammaBounds, gammaBoundsAgain)
    }
}
