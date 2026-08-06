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
import java.util.Date
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end (Robolectric, no emulator) coverage of bead vn-edu.67's
 * Gmail-style swipe-to-delete/archive gestures on Sessions-screen rows --
 * updated by asn-638 for the inline pending-row Undo (a dimmed row +
 * on-row Undo button, not a snackbar) -- driven through the real
 * [AppNavHost] + [SessionListScreen] against a real [VoiceCaptureApp] --
 * same no-network approach [SessionDeleteInteractionTest] and
 * [AppNavHostInteractionTest] use. Swipes are performed on the row's
 * `session-swipe-<id>` testTag (see [SessionRow]'s KDoc for why -- the
 * visible title text's own bounds are too narrow to cross
 * SwipeToDismissBox's positional threshold). asn-638's own state-machine
 * (schedule/undo/flush/flushAll) lives in
 * [com.montauk.voicecapture.session.PendingRemovalHolderTest]; the actual
 * 10s countdown timing lives in
 * [com.montauk.voicecapture.session.PendingRemovalCountdownTest]. This suite
 * covers the swipe gesture -> pending-row transition, concurrent pending
 * rows, and the screen-exit commit path end to end, against the real
 * Compose tree.
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
        PendingRemovalHolder.flushAll()
        SwipeHintStateHolder.clear()
    }

    @After
    fun tearDown() {
        PendingRemovalHolder.flushAll()
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
    fun `left-swipe on an UPLOADED session enters the inline pending state and Undo restores it`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        // asn-638: the row stays in place (dimmed) with its own inline Undo
        // instead of disappearing behind a shared snackbar -- title text is
        // still on screen, and the pending-row testTag confirms it's the
        // dimmed/Undo variant, not the normal swipeable one.
        composeTestRule.onNodeWithTag("session-pending-2026-08-01_0900_ab12").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
        assertTrue("the 10s window must defer the actual delete -- file still on disk", dir.exists())
        composeTestRule.onNodeWithText("Deleted").assertIsDisplayed()

        composeTestRule.onNodeWithTag("session-undo-2026-08-01_0900_ab12").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Undo must leave the file untouched", dir.exists())
        composeTestRule.onNodeWithTag("session-pending-2026-08-01_0900_ab12").assertDoesNotExist()
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
    fun `right-swipe on an INTEGRATED session enters the inline pending state and Undo restores it`() {
        useVaultReader(integratedSessionIds = setOf("2026-08-01_0900_ab12"))
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-pending-2026-08-01_0900_ab12").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
        assertTrue("the 10s window must defer the actual archive -- file still on disk", dir.exists())
        composeTestRule.onNodeWithText("Archived").assertIsDisplayed()

        composeTestRule.onNodeWithTag("session-undo-2026-08-01_0900_ab12").performClick()
        composeTestRule.waitForIdle()

        assertTrue(dir.exists())
        composeTestRule.onNodeWithTag("session-pending-2026-08-01_0900_ab12").assertDoesNotExist()
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

    @Test
    fun `two rows can be pending at once, independently -- undoing one leaves the other's countdown running`() {
        // The second session must be INTEGRATED for its right-swipe to
        // archive instantly (see SessionRow's confirmValueChange) -- an
        // ineligible one would settle back with a hint instead of pending.
        useVaultReader(integratedSessionIds = setOf("2026-08-01_1000_cd34"))
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(
            app.sessionStore,
            "2026-08-01_1000_cd34",
            "Marathon training recap",
            uploadState = UploadState.UPLOADED,
            startedAt = Date(SessionFixtures.FIXED_STARTED_AT.time + 3_600_000L),
        )
        val dirDeleted = app.sessionStore.sessionDir("2026-08-01_0900_ab12")
        val dirArchived = app.sessionStore.sessionDir("2026-08-01_1000_cd34")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        // asn-638: swipe BOTH rows before either resolves -- one delete, one
        // archive, on the same screen -- and confirm each gets its own
        // independent pending UI (no more of vn-edu.67's Gmail-style
        // "scheduling a second one flushes the first").
        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_1000_cd34").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-pending-2026-08-01_0900_ab12").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-pending-2026-08-01_1000_cd34").assertIsDisplayed()
        assertTrue("first swipe must not have been flushed by the second", dirDeleted.exists())
        assertTrue(dirArchived.exists())

        composeTestRule.onNodeWithTag("session-undo-2026-08-01_0900_ab12").performClick()
        composeTestRule.waitForIdle()

        assertTrue("undone row's file must be untouched", dirDeleted.exists())
        composeTestRule.onNodeWithTag("session-pending-2026-08-01_0900_ab12").assertDoesNotExist()
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
        // The OTHER row's pending state must survive the first one's undo untouched.
        composeTestRule.onNodeWithTag("session-pending-2026-08-01_1000_cd34").assertIsDisplayed()
        assertTrue(dirArchived.exists())
    }

    @Test
    fun `leaving the Sessions screen while a row is pending commits it immediately, without waiting out the countdown`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(
            app.sessionStore,
            "2026-08-01_1000_cd34",
            "Marathon training recap",
            uploadState = UploadState.UPLOADED,
            startedAt = Date(SessionFixtures.FIXED_STARTED_AT.time + 3_600_000L),
        )
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-swipe-2026-08-01_0900_ab12").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("session-pending-2026-08-01_0900_ab12").assertIsDisplayed()
        assertTrue("still within the 10s window -- not committed yet", dir.exists())

        // Navigate away from Sessions by tapping the OTHER (non-pending) row,
        // which opens its session-detail screen -- NavHost fully disposes
        // SessionListScreen at that point, which must flush every
        // still-pending row rather than lose the commit.
        composeTestRule.onNodeWithText("Marathon training recap").performClick()
        composeTestRule.waitForIdle()

        assertFalse("leaving the Sessions screen must commit any still-pending removal immediately, not lose it", dir.exists())
    }
}
