package com.montauk.voicecapture.duck

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.montauk.voicecapture.service.SummaryUiState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Component-level coverage of [NotesCard] (bead asn-dp2) that doesn't need
 * the full [com.montauk.voicecapture.ui.RecordingScreen] wiring: rendering
 * every bullet, tap-to-pin, the newest-bullet highlight, and the reduced-
 * motion fallback (which needs [reducedMotion] forced true -- not reachable
 * through the real [rememberReducedMotionEnabled] system-setting read from a
 * full-screen test). End-to-end wiring (the notes card's write-pose
 * coordination with [DuckAnimator]) is
 * [com.montauk.voicecapture.ui.RecordingScreenLayoutATest]'s job;
 * [NotesCardChoreographerTest] covers the pure enter/hold/exit-tail timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun summaryWith(vararg bullets: String): SummaryUiState =
        SummaryUiState(bullets = bullets.toList(), newestIndex = bullets.lastIndex, updatedAtMs = 1L)

    @Test
    fun `renders every bullet`() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = summaryWith("first bullet", "second bullet"))
            }
        }

        composeTestRule.onNodeWithText("first bullet", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("second bullet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tap pins the card open -- it does not auto-hide`() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = summaryWith("first bullet"))
            }
        }

        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertIsDisplayed()
    }

    // Bead asn-dp2: reduced-motion fallback -- not reachable through the real
    // system-setting read at the RecordingScreen level, so exercised
    // directly here via the explicit reducedMotion parameter.

    @Test
    fun `reduced motion still renders the card and its newest bullet immediately, no crash`() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = summaryWith("first bullet"), reducedMotion = true)
            }
        }

        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertIsDisplayed()
        // useUnmergedTree: NotesCardContent's Column is clickable (tap to
        // pin), and Compose's accessibility semantics merge every
        // descendant's testTag into that single clickable node by default --
        // the newest bullet's own testTag only shows up as its own node in
        // the UNMERGED tree.
        composeTestRule.onNodeWithTag(NOTES_CARD_NEWEST_BULLET_TEST_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no bullets yet renders nothing`() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = SummaryUiState())
            }
        }

        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertDoesNotExist()
    }
}
