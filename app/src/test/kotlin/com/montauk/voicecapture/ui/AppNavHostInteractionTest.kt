package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.testutil.FakeVaultSessionSource
import com.montauk.voicecapture.testutil.SessionFixtures
import com.montauk.voicecapture.vault.VaultSessionCache
import com.montauk.voicecapture.vault.VaultSessionReader
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose interaction tests for [AppNavHost] on Robolectric/JVM -- no
 * emulator, no device (bead vn-edu.35). Drives the real nav graph + real
 * screens against a real (Robolectric-provided) [VoiceCaptureApp], seeding
 * on-disk session fixtures via [SessionFixtures] and the two global
 * `*StateHolder` singletons directly rather than a running
 * `RecordingService` -- these tests own the `onStopRecording` /
 * `onNewSessionTapped` callbacks that [com.montauk.voicecapture.ui.MainActivity]
 * would otherwise wire to the service, so they exercise navigation + layout
 * without ever starting a foreground service.
 *
 * [sessionsListRendersAboveBottomAnchoredNav], [tappingASessionOpensDetail],
 * and [stopReturnsToSessionsWithNewSessionVisible] are the tests that catch
 * the vn-edu.33 nav regression: the debug-only "New Session" tab item
 * currently measures/places itself across the *entire* screen height
 * (0..root height) instead of the nav bar's own ~80dp band, which both
 * mis-anchors the bottom nav and silently swallows taps meant for content
 * behind it. All three fail against the unfixed BottomNavBar.kt on `main` as
 * of this writing (`fix-nav` owns the fix, bead vn-edu.33) -- see README's
 * "UI tests" section for the exact failure signatures used to confirm that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNavHostInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Belt-and-suspenders: Robolectric sandboxes statics per test already,
        // but these two are process-global singletons in production, so reset
        // them explicitly rather than depending on that isolation.
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        // Bead vn-edu.54: SessionListScreen now fetches vault-integration
        // status on every mount. connectedToGithubShowsLogOutTab below sets a
        // (fake) non-blank GitHub token while rendering Routes.SESSIONS, which
        // without this override would make a real, unmocked HTTPS call to
        // GitHub's Contents API. Every test in this class gets a deterministic,
        // no-network reader regardless of whether it touches sign-in state.
        app.vaultSessionReader = VaultSessionReader(VaultSessionCache(File(app.cacheDir, "vault-session-cache-test")), FakeVaultSessionSource())
    }

    /** The bottom nav's tab for [label] -- distinguishes it from same-text screen headlines (e.g. "Sessions" / "Settings"). */
    private fun navTab(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction())

    @Test
    fun sessionsListRendersAboveBottomAnchoredNav() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        val rootBottom = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val navBounds = navTab("Sessions").fetchSemanticsNode().boundsInRoot
        val rowBounds = composeTestRule.onNodeWithText("Kitchen remodel budget check").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "bottom nav must be anchored at the screen's bottom edge (nav bottom=${navBounds.bottom}, root bottom=$rootBottom)",
            rootBottom - navBounds.bottom < rootBottom * 0.1f,
        )
        assertTrue(
            "session row content must render fully above the nav bar, not overlapping/under it " +
                "(row bottom=${rowBounds.bottom}, nav top=${navBounds.top})",
            rowBounds.bottom <= navBounds.top,
        )
    }

    @Test
    fun tappingASessionOpensDetail() {
        SessionFixtures.seedSession(app.sessionStore, "2026-08-01_0900_ab12", "Kitchen remodel budget check")

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Kitchen remodel budget check").performClick()
        composeTestRule.waitForIdle()

        // Bead vn-edu.42: the detail header now shows the (here, derived --
        // no meta.title set by this fixture) title instead of the raw
        // session id it used to always show, so it legitimately duplicates
        // the list row's text -- assert on the detail screen's Back button
        // instead, which only exists once we've actually navigated there.
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Kitchen remodel budget check").assertCountEquals(2)
    }

    @Test
    fun newSessionNavigatesToRecording() {
        var tapped = false

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = { tapped = true }, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        navTab("New Session").performClick()
        composeTestRule.waitForIdle()

        assertTrue("tapping New Session should invoke onNewSessionTapped", tapped)
        composeTestRule.onNodeWithText("STOP").assertIsDisplayed()
    }

    @Test
    fun modeSwitcherDisabledTabsDoNotInvokeCallback() {
        val setModeCalls = mutableListOf<RecordingMode>()
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(
                startDestination = Routes.RECORDING,
                onNewSessionTapped = {},
                onStopRecording = {},
                onSetMode = { setModeCalls += it },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Converse, coming soon").performClick()
        composeTestRule.waitForIdle()

        assertTrue("tapping a disabled mode segment must not invoke onSetMode", setModeCalls.isEmpty())
        // Still on the recording screen -- a disabled tab must not navigate away either.
        composeTestRule.onNodeWithText("STOP").assertIsDisplayed()
    }

    @Test
    fun stopReturnsToSessionsWithNewSessionVisible() {
        // Reach Recording the same way a real user does -- Sessions, then New
        // Session -- so the back stack is [sessions, recording] exactly like
        // AppNavHost's onStopRecording KDoc assumes for its popUpTo(SESSIONS)
        // call. Starting straight on Routes.RECORDING (skipping Sessions)
        // leaves no existing "sessions" entry for that popUpTo to collapse
        // into, which isn't how MainActivity ever actually gets here.
        composeTestRule.setContent {
            AppNavHost(
                startDestination = Routes.SESSIONS,
                onNewSessionTapped = {},
                onStopRecording = {
                    // Stands in for RecordingService's real finalize-on-stop path:
                    // by the time Stop navigates to Sessions, the new session's
                    // meta.json already exists on disk.
                    SessionFixtures.seedSession(app.sessionStore, "2026-08-01_1000_cd34", "Marathon training recap")
                },
            )
        }
        composeTestRule.waitForIdle()

        navTab("New Session").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("STOP").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Marathon training recap").assertIsDisplayed()
        navTab("Sessions").assertIsDisplayed()
    }

    @Test
    fun signedOutShowsSignInTab() {
        app.secretsStore.isSignedOut = true

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        navTab("Sign In").assertIsDisplayed()
        composeTestRule.onNodeWithText("Log Out").assertDoesNotExist()
    }

    @Test
    fun connectedToGithubShowsLogOutTab() {
        app.secretsStore.userGithubToken = "ghp_test_token_1234"
        app.secretsStore.isSignedOut = false

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        navTab("Log Out").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign In").assertDoesNotExist()
    }
}
