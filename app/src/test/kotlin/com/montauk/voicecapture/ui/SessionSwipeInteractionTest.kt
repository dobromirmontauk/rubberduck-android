package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.PendingRemovalHolder
import com.montauk.voicecapture.session.SwipeHintStateHolder
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.testutil.FakeVaultSessionSource
import com.montauk.voicecapture.testutil.SessionFixtures
import com.montauk.voicecapture.vault.VaultSessionCache
import com.montauk.voicecapture.vault.VaultSessionReader
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end (Robolectric, no emulator) coverage of bead vn-edu.67's
 * Gmail-style swipe-to-delete/archive gestures on Sessions-screen rows,
 * driven through the real [AppNavHost] + [SessionListScreen] against a real
 * [VoiceCaptureApp] -- same no-network approach [SessionDeleteInteractionTest]
 * and [AppNavHostInteractionTest] use. Swipes are performed on the row's
 * `session-swipe-<id>` testTag (see [SessionRow]'s KDoc for why -- the
 * visible title text's own bounds are too narrow to cross
 * SwipeToDismissBox's positional threshold).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSwipeInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        app.vaultSessionReader = VaultSessionReader(VaultSessionCache(File(app.cacheDir, "vault-session-cache-test")), FakeVaultSessionSource())
        // Both are process-global singletons in production (see their own
        // KDocs) -- reset explicitly rather than relying on Robolectric's
        // per-test static sandboxing, same belt-and-suspenders as
        // AppNavHostInteractionTest does for RecordingStateHolder/TranscriptStateHolder.
        PendingRemovalHolder.flush()
        SwipeHintStateHolder.clear()
    }

    @After
    fun tearDown() {
        PendingRemovalHolder.flush()
        SwipeHintStateHolder.clear()
    }

    private fun useVaultReader(integratedSessionIds: Set<String>) {
        app.vaultSessionReader = VaultSessionReader(
            VaultSessionCache(File(app.cacheDir, "vault-session-cache-test")),
            FakeVaultSessionSource(integratedSessionIds),
        )
        app.secretsStore.userGithubToken = "ghp_test_token"
        app.secretsStore.isSignedOut = false
    }

    @Test
    fun `left-swipe on an UPLOADED session deletes instantly and Undo restores it`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertDoesNotExist()
        assertTrue("undo window must defer the actual delete -- file still on disk", dir.exists())
        composeTestRule.onNodeWithText("Deleted").assertIsDisplayed()

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Undo must leave the file untouched", dir.exists())
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
    }

    @Test
    fun `left-swipe on a LOCAL session opens the hard-confirm dialog instead of deleting instantly`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.LOCAL)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete unsaved recording?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not uploaded — this recording will be lost forever.").assertIsDisplayed()
        assertTrue("no instant destruction of unuploaded audio", dir.exists())
        // The swipe itself never committed -- the row is still present underneath the dialog.
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
    }

    @Test
    fun `right-swipe on an INTEGRATED session archives instantly and Undo restores it`() {
        useVaultReader(integratedSessionIds = setOf("2026-08-01_0900_ab12"))
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertDoesNotExist()
        assertTrue("undo window must defer the actual archive -- file still on disk", dir.exists())
        composeTestRule.onNodeWithText("Archived").assertIsDisplayed()

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        assertTrue(dir.exists())
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
    }

    @Test
    fun `right-swipe on a non-INTEGRATED session settles back with a hint and leaves the row untouched`() {
        useVaultReader(integratedSessionIds = emptySet())
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Not yet integrated").assertIsDisplayed()
        assertTrue(dir.exists())
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
    }
}
