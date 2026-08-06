package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.testutil.FakeBundleUploader
import com.montauk.voicecapture.testutil.FakeVaultSessionSource
import com.montauk.voicecapture.testutil.SessionFixtures
import com.montauk.voicecapture.vault.VaultSessionCache
import com.montauk.voicecapture.vault.VaultSessionReader
import com.montauk.voicecapture.vault.VaultSessionSource
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end (Robolectric, no emulator) coverage of bead vn-edu.55's
 * delete-from-phone (per-session, long-press) and bulk-archive-integrated
 * (Sessions screen top action) flows, driven through the real [AppNavHost] +
 * screens against a real [VoiceCaptureApp] -- same approach
 * [SessionListVaultStatusTest] and [AppNavHostInteractionTest] use for the
 * same no-network reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDeleteInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        app.vaultSessionReader = VaultSessionReader(VaultSessionCache(File(app.cacheDir, "vault-session-cache-test")), FakeVaultSessionSource())
    }

    private fun useVaultReader(integratedSessionIds: Set<String>) {
        app.vaultSessionReader = VaultSessionReader(
            VaultSessionCache(File(app.cacheDir, "vault-session-cache-test")),
            FakeVaultSessionSource(integratedSessionIds),
        )
        // VaultSessionReader.refresh short-circuits to EMPTY on a blank token
        // (see its KDoc) regardless of what the source would return -- needs
        // a non-blank (fake) token for the bulk-archive eligibility this
        // reader feeds to reflect integratedSessionIds at all.
        app.secretsStore.userGithubToken = "ghp_test_token"
        app.secretsStore.isSignedOut = false
    }

    /** Forces [VaultSessionReader.refresh]'s stale-cache branch: a source that always fails, with [integratedSessionIds] pre-seeded in the cache from an earlier "successful" fetch. */
    private fun useStaleVaultReader(integratedSessionIds: Set<String>) {
        val cache = VaultSessionCache(File(app.cacheDir, "vault-session-cache-test-stale"))
        cache.save(integratedSessionIds, fetchedAtMs = 1_000L)
        val alwaysFailingSource = object : VaultSessionSource {
            override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? = null
            override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? = null
        }
        app.vaultSessionReader = VaultSessionReader(cache, alwaysFailingSource)
        app.secretsStore.userGithubToken = "ghp_test_token"
        app.secretsStore.isSignedOut = false
    }

    // ---- Per-session delete: the two confirm variants ----

    @Test
    fun `long-press on an UPLOADED session shows a plain confirm and Delete is enabled immediately`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete this session?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsEnabled()
    }

    @Test
    fun `long-press on a LOCAL session shows the hard warning and Delete stays disabled until acknowledged`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.LOCAL)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete unsaved recording?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not uploaded — this recording will be lost forever.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsNotEnabled()

        composeTestRule.onNodeWithText("I understand this can't be undone").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete").assertIsEnabled()
    }

    @Test
    fun `confirming delete removes the session directory from disk and the row disappears immediately`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitForIdle()

        assertTrue("session directory must be gone on disk", !dir.exists())
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertDoesNotExist()
    }

    @Test
    fun `cancelling the delete confirm leaves the session on disk and in the list`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        assertTrue(dir.exists())
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertIsDisplayed()
    }

    @Test
    fun `deleting a session never invokes the bundle uploader`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val fakeUploader = FakeBundleUploader()
        app.bundleUploader = fakeUploader

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitForIdle()

        assertTrue("delete-from-phone must never call the uploader", fakeUploader.calls.isEmpty())
    }

    // ---- Detail screen's Delete action ----

    @Test
    fun `detail screen Delete action on an UPLOADED session confirms and navigates back to Sessions`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val dir = app.sessionStore.sessionDir("2026-08-01_0900_ab12")

        // Reach Detail the same way a real user does -- Sessions, then tap the
        // row -- so the back stack has a "sessions" entry underneath for
        // onBack's popBackStack() to land on (starting straight on
        // Routes.sessionDetail(...) leaves nothing to pop to, the same trap
        // AppNavHostInteractionTest's stopReturnsToSessionsWithNewSessionVisible
        // documents for Routes.RECORDING).
        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Kitchen remodel budget check").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Delete from phone").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Delete this session?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitForIdle()

        assertTrue(!dir.exists())
        // Back on Sessions -- the detail screen's Back button no longer exists.
        composeTestRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `detail screen Delete action on a LOCAL session shows the hard warning too`() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.LOCAL)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.sessionDetail("2026-08-01_0900_ab12"), onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Delete from phone").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Delete unsaved recording?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsNotEnabled()
    }

    // ---- Bulk archive ----

    @Test
    fun `bulk archive action is disabled when there are zero integrated sessions`() {
        useVaultReader(integratedSessionIds = emptySet())
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Archive all integrated sessions").assertIsNotEnabled()
    }

    @Test
    fun `bulk archive deletes only INTEGRATED sessions, shows the count, and updates the list immediately`() {
        useVaultReader(integratedSessionIds = setOf("integrated-1", "integrated-2"))
        SessionFixtures.seedSession(app.sessionStore, "integrated-1", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(app.sessionStore, "integrated-2", "Marathon training recap", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(app.sessionStore, "uploaded-not-integrated", "Deck repair notes", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(app.sessionStore, "local-only", "Dog walk download", uploadState = UploadState.LOCAL)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Archive all integrated sessions").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Archive all integrated sessions").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Archive 2 integrated sessions?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Archive").performClick()
        composeTestRule.waitForIdle()

        assertTrue(!app.sessionStore.sessionDir("integrated-1").exists())
        assertTrue(!app.sessionStore.sessionDir("integrated-2").exists())
        assertTrue("a merely-UPLOADED (not integrated) session must survive", app.sessionStore.sessionDir("uploaded-not-integrated").exists())
        assertTrue("a LOCAL session must survive", app.sessionStore.sessionDir("local-only").exists())
        composeTestRule.onNodeWithText("Kitchen remodel budget check").assertDoesNotExist()
        composeTestRule.onNodeWithText("Marathon training recap").assertDoesNotExist()
        composeTestRule.onNodeWithText("Deck repair notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dog walk download").assertIsDisplayed()
    }

    @Test
    fun `bulk archive is disabled when the integration snapshot is stale, even if the cache reports integrated sessions`() {
        useStaleVaultReader(integratedSessionIds = setOf("2026-08-01_0900_ab12"))
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Archive all integrated sessions").assertIsNotEnabled()
    }

    @Test
    fun `bulk archive never invokes the bundle uploader`() {
        useVaultReader(integratedSessionIds = setOf("2026-08-01_0900_ab12"))
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        val fakeUploader = FakeBundleUploader()
        app.bundleUploader = fakeUploader

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Archive all integrated sessions").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Archive").performClick()
        composeTestRule.waitForIdle()

        assertTrue("bulk archive must never call the uploader", fakeUploader.calls.isEmpty())
    }
}
