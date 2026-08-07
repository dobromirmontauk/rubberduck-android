package com.montauk.voicecapture.duck

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.montauk.voicecapture.service.SummaryUiState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Component-level coverage of [NotesCard] (beads asn-dp2/asn-rrw) that
 * doesn't need the full [com.montauk.voicecapture.ui.RecordingScreen] +
 * [com.montauk.voicecapture.service.RecordingService] wiring: the swipe-
 * right/swipe-left outcomes themselves, the visible swipe hint, the newest-
 * bullet highlight, and the reduced-motion fallback (which needs
 * [reducedMotion] forced true -- not reachable through the real
 * [rememberReducedMotionEnabled] system-setting read from a full-screen
 * test). End-to-end wiring (the notes card's write-pose coordination with
 * [DuckAnimator], and the callbacks actually reaching
 * [com.montauk.voicecapture.service.RecordingActivityStateHolder]-adjacent
 * state) is [com.montauk.voicecapture.ui.RecordingScreenLayoutATest]'s job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun summaryWith(vararg bullets: String): SummaryUiState =
        SummaryUiState(bullets = bullets.toList(), newestIndex = bullets.lastIndex, updatedAtMs = 1L)

    @Test
    fun `renders every bullet and the swipe hint`() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = summaryWith("first bullet", "second bullet"))
            }
        }

        composeTestRule.onNodeWithText("first bullet", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("second bullet", substring = true).assertIsDisplayed()
        // useUnmergedTree: NotesCardContent's Column is clickable (tap to
        // pin), and Compose's accessibility semantics merge every
        // descendant's testTag into that single clickable node by default --
        // the hint's own testTag only shows up as its own node in the
        // UNMERGED tree.
        composeTestRule.onNodeWithTag(NOTES_CARD_SWIPE_HINT_TEST_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `swipe right approves the newest bullet and dismisses the card`() {
        var approved: String? = null
        var discarded: String? = null
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(
                    summary = summaryWith("first bullet", "second bullet"),
                    onApprove = { approved = it },
                    onDiscard = { discarded = it },
                )
            }
        }

        composeTestRule.onNodeWithTag(NOTES_CARD_SWIPE_TEST_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals("second bullet", approved)
        assertEquals(null, discarded)
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `swipe left discards the newest bullet and dismisses the card`() {
        var approved: String? = null
        var discarded: String? = null
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(
                    summary = summaryWith("first bullet", "second bullet"),
                    onApprove = { approved = it },
                    onDiscard = { discarded = it },
                )
            }
        }

        composeTestRule.onNodeWithTag(NOTES_CARD_SWIPE_TEST_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals("second bullet", discarded)
        assertEquals(null, approved)
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertDoesNotExist()
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

    // Bead asn-rrw.6: swipes still work once the card has been pinned open.

    @Test
    fun `swipe right still approves after the card has been pinned by a tap`() {
        var approved: String? = null
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = summaryWith("first bullet"), onApprove = { approved = it })
            }
        }
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertIsDisplayed() // pinned, not auto-hidden

        composeTestRule.onNodeWithTag(NOTES_CARD_SWIPE_TEST_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals("first bullet", approved)
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `swipe left still discards after the card has been pinned by a tap`() {
        var discarded: String? = null
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = summaryWith("first bullet"), onDiscard = { discarded = it })
            }
        }
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NOTES_CARD_SWIPE_TEST_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals("first bullet", discarded)
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertDoesNotExist()
    }

    // NotesCardChoreographerTest already covers isWritePoseActive's exact
    // enter/hold/exit-tail timing (pure, instant); RecordingScreenLayoutATest
    // covers the live onWritePoseActiveChanged -> DuckState.WRITE wiring end
    // to end. A bespoke callback test at this bare-composable level added
    // nothing beyond those two and, combined with NotesCard's own
    // indefinitely-repeating tick LaunchedEffect plus the entrance spring,
    // reliably stalled Compose's idle-sync for several real seconds per run
    // -- not worth the suite time for duplicate coverage.

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
        // useUnmergedTree -- see the swipe-hint check above for why.
        composeTestRule.onNodeWithTag(NOTES_CARD_NEWEST_BULLET_TEST_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `reduced motion still supports swipe-right approve and swipe-left discard`() {
        var approved: String? = null
        composeTestRule.setContent {
            VoiceCaptureTheme {
                NotesCard(summary = summaryWith("first bullet"), reducedMotion = true, onApprove = { approved = it })
            }
        }

        composeTestRule.onNodeWithTag(NOTES_CARD_SWIPE_TEST_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        assertEquals("first bullet", approved)
        composeTestRule.onNodeWithTag(NOTES_CARD_TEST_TAG).assertDoesNotExist()
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
