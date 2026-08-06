package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.auth.GitHubAccountClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.30 acceptance criteria for the login screen's PAT entry
 * ([TokenEntryBlock]/`submitToken`): a token is never persisted to
 * [com.montauk.voicecapture.settings.AppSecretsStore] -- and [onSignedIn]
 * never fires -- until [GitHubAccountClient.validateForVault] has actually
 * succeeded against the configured vault repo, and an over-scoped-but-valid
 * token routes through a one-tap confirmation rather than signing in
 * silently. Points the injected [GitHubAccountClient] at a [MockWebServer]
 * -- same boundary as [IntelligenceStepTest] and [GitHubAccountClientTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        app = ApplicationProvider.getApplicationContext()
        // Same determinism concern as KeyScreensScreenshotTest -- this
        // screen must behave the same regardless of whatever real
        // local.properties happened to build this test JVM.
        app.secretsStore.isSignedOut = true
        app.secretsStore.userGithubToken = null
        app.secretsStore.selectedVaultOwner = "dobromirmontauk"
        app.secretsStore.selectedVaultRepo = "voice-vault"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun setContent(onSignedIn: () -> Unit = {}) {
        composeTestRule.setContent {
            LoginScreen(
                onSignedIn = onSignedIn,
                accountClient = GitHubAccountClient(apiBaseUrl = server.url("/").toString().trimEnd('/')),
            )
        }
    }

    private fun openTokenEntryAndSubmit(token: String) {
        composeTestRule.onNodeWithText("Use an access token").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(token)
        composeTestRule.onNodeWithText("Continue").performClick()
    }

    private fun waitUntilSettled(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis) { condition() }
    }

    @Test
    fun `a token that can't reach the vault repo is never persisted, and onSignedIn never fires`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"login":"dobromirmontauk","avatar_url":null}"""))
        server.enqueue(MockResponse().setResponseCode(404)) // /repos/dobromirmontauk/voice-vault
        var signedIn = false

        setContent(onSignedIn = { signedIn = true })
        openTokenEntryAndSubmit("scoped-elsewhere-token")
        waitUntilSettled { server.requestCount >= 2 }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("That token can't reach dobromirmontauk/voice-vault").assertIsDisplayed()
        assertNull(app.secretsStore.userGithubToken)
        assertFalse(signedIn)
    }

    @Test
    fun `a valid fine-grained-shaped token (no X-OAuth-Scopes header) signs in immediately, no warning screen`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"login":"dobromirmontauk","avatar_url":null}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"voice-vault"}"""))
        var signedIn = false

        setContent(onSignedIn = { signedIn = true })
        openTokenEntryAndSubmit("fine-grained-pat")
        waitUntilSettled { signedIn }

        assertEquals("fine-grained-pat", app.secretsStore.userGithubToken)
        assertEquals("dobromirmontauk", app.secretsStore.userGithubLogin)
        assertTrue(signedIn)
    }

    @Test
    fun `an over-scoped but working token is not persisted until Use anyway is tapped`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("X-OAuth-Scopes", "repo")
                .setBody("""{"login":"dobromirmontauk","avatar_url":null}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"voice-vault"}"""))
        var signedIn = false

        setContent(onSignedIn = { signedIn = true })
        openTokenEntryAndSubmit("classic-token-with-repo-access")
        waitUntilSettled { server.requestCount >= 2 }
        composeTestRule.waitForIdle()

        // Gated: the warning screen is up, nothing persisted yet.
        composeTestRule.onNodeWithText("Use anyway").assertIsDisplayed()
        assertNull(app.secretsStore.userGithubToken)
        assertFalse(signedIn)

        composeTestRule.onNodeWithText("Use anyway").performClick()
        waitUntilSettled { signedIn }

        assertEquals("classic-token-with-repo-access", app.secretsStore.userGithubToken)
        assertTrue(signedIn)
    }
}
