package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.testutil.FakeVaultSessionSource
import com.montauk.voicecapture.testutil.SessionFixtures
import com.montauk.voicecapture.vault.VaultSessionCache
import com.montauk.voicecapture.vault.VaultSessionReader
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end (Robolectric, no emulator) coverage of the Sessions screen's
 * INTEGRATED status (bead vn-edu.54): a real [VoiceCaptureApp] +
 * [SessionListScreen], with [FakeVaultSessionSource] standing in for the
 * real GitHub Contents API so this stays deterministic and offline -- same
 * approach [AppNavHostInteractionTest] now uses for exactly this reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionListVaultStatusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        app.secretsStore.userGithubToken = "ghp_test_token"
        app.secretsStore.isSignedOut = false
    }

    private fun useVaultReader(integratedSessionIds: Set<String>) {
        app.vaultSessionReader = VaultSessionReader(
            VaultSessionCache(File(app.cacheDir, "vault-session-cache-test")),
            FakeVaultSessionSource(integratedSessionIds),
        )
    }

    @Test
    fun `an UPLOADED session the vault listing hits renders INTEGRATED`() {
        useVaultReader(integratedSessionIds = setOf("2026-08-01_0900_ab12"))
        SessionFixtures.seedSession(
            app.sessionStore,
            sessionId = "2026-08-01_0900_ab12",
            transcriptText = "Kitchen remodel budget check",
            uploadState = UploadState.UPLOADED,
        )

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("INTEGRATED").assertIsDisplayed()
    }

    @Test
    fun `an UPLOADED session the vault listing does NOT hit stays UPLOADED`() {
        useVaultReader(integratedSessionIds = setOf("some-other-session-id"))
        SessionFixtures.seedSession(
            app.sessionStore,
            sessionId = "2026-08-01_0900_ab12",
            transcriptText = "Kitchen remodel budget check",
            uploadState = UploadState.UPLOADED,
        )

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("UPLOADED").assertIsDisplayed()
    }

    @Test
    fun `signed out -- keyless -- never shows INTEGRATED even if a session id happens to match a leftover cache`() {
        useVaultReader(integratedSessionIds = setOf("2026-08-01_0900_ab12"))
        SessionFixtures.seedSession(
            app.sessionStore,
            sessionId = "2026-08-01_0900_ab12",
            transcriptText = "Kitchen remodel budget check",
            uploadState = UploadState.UPLOADED,
        )
        app.secretsStore.isSignedOut = true

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("UPLOADED").assertIsDisplayed()
    }
}
