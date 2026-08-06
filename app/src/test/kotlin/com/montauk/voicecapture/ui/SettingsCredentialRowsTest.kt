package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.montauk.voicecapture.settings.CredentialDisplayState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.52 acceptance criteria: the Settings "GitHub" row and "Upload
 * token" row each need a third state -- a dev/build token can be actively
 * uploading while signed out ([GithubConnectionRow]'s secondary notice) or
 * without any runtime-entered token ([UploadTokenRow]'s
 * [CredentialDisplayState.DEV_FALLBACK]). Exercises both composables
 * directly with explicit state (no real [com.montauk.voicecapture.BuildConfig]
 * dependency -- that's what makes this deterministic across machines/CI
 * regardless of `local.properties`) -- [SettingsScreen] wires the real
 * [com.montauk.voicecapture.VoiceCaptureApp]/`AppSecretsStore` state in
 * production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCredentialRowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `signed out with no dev fallback shows Not connected and no secondary notice`() {
        composeTestRule.setContent {
            GithubConnectionRow(connected = false, githubLogin = null, showDevFallbackNotice = false, onConnectGithub = {})
        }

        composeTestRule.onNodeWithText("Not connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect GitHub").assertIsDisplayed()
        composeTestRule.onNodeWithText("Uploads active via build-time token (dev)").assertDoesNotExist()
    }

    @Test
    fun `signed out with a dev fallback active shows Not connected plus the uploads-active notice`() {
        composeTestRule.setContent {
            GithubConnectionRow(connected = false, githubLogin = null, showDevFallbackNotice = true, onConnectGithub = {})
        }

        composeTestRule.onNodeWithText("Not connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Uploads active via build-time token (dev)").assertIsDisplayed()
    }

    @Test
    fun `connected shows the login and never the dev fallback notice, even if the flag is set`() {
        composeTestRule.setContent {
            GithubConnectionRow(connected = true, githubLogin = "dobromirmontauk", showDevFallbackNotice = true, onConnectGithub = {})
        }

        composeTestRule.onNodeWithText("dobromirmontauk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect GitHub").assertDoesNotExist()
        composeTestRule.onNodeWithText("Uploads active via build-time token (dev)").assertDoesNotExist()
    }

    @Test
    fun `upload token row NOT_CONFIGURED shows Not configured`() {
        composeTestRule.setContent { UploadTokenRow(state = CredentialDisplayState.NOT_CONFIGURED) }

        composeTestRule.onNodeWithText("Not configured").assertIsDisplayed()
    }

    @Test
    fun `upload token row DEV_FALLBACK shows the build-time token dev state`() {
        composeTestRule.setContent { UploadTokenRow(state = CredentialDisplayState.DEV_FALLBACK) }

        composeTestRule.onNodeWithText("Using build-time token (dev)").assertIsDisplayed()
    }

    @Test
    fun `upload token row CONFIGURED shows the in-app configured state`() {
        composeTestRule.setContent { UploadTokenRow(state = CredentialDisplayState.CONFIGURED) }

        composeTestRule.onNodeWithText("Configured (in-app)").assertIsDisplayed()
    }
}
