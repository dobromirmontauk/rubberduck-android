package com.montauk.voicecapture.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptLine
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.service.TranscriptUiState
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.stt.SttConnectionState
import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.TagTier
import com.montauk.voicecapture.testutil.SessionFixtures
import com.montauk.voicecapture.ui.AppNavHost
import com.montauk.voicecapture.ui.Routes
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Date

/**
 * Roborazzi screenshot goldens for the app's key screens (bead vn-edu.34) --
 * JVM/Robolectric, no emulator/device. Golden PNGs are committed under
 * `src/test/screenshot/goldens/` (see the `roborazzi { outputDir.set(...) }`
 * block in `build.gradle.kts`); `./gradlew verifyRoborazziDebug` re-renders
 * each screen and fails the build on any pixel diff. See README's
 * "Screenshot tests (Roborazzi)" section for the record/verify workflow.
 *
 * Every screen here is rendered from fixed fixture state -- fixed session
 * ids/dates/transcript text (no `Date()`/`System.currentTimeMillis()`/
 * `Random`), a fixed device ([RobolectricDeviceQualifiers.Pixel7]), and the
 * app's theme is always dark regardless of system setting (see
 * `VoiceCaptureTheme`) -- so a rerun with no code changes reproduces the
 * exact same PNG bytes.
 *
 * Bead vn-edu.40: that fixed-fixture guarantee also has to cover
 * `AppSecretsStore`'s `effective*()`/`selectedVault*` getters, which fall
 * back to `BuildConfig.ASSEMBLYAI_API_KEY` / `GITHUB_TOKEN` / `VAULT_OWNER` /
 * `VAULT_REPO` -- values baked in at build time from *this machine's*
 * `local.properties`, not from any fixture here. [setUp] pins all of that
 * explicitly (`isSignedOut = true` forces both `effective*()` getters to ""
 * unconditionally; `selectedVaultOwner`/`selectedVaultRepo` bypass their
 * BuildConfig fallback) so [settings] renders identically whether the
 * machine running this test has real keys configured in `local.properties`
 * or not. Verified against both a keyless clone and a clone carrying real
 * `assemblyai.apiKey` / `github.token` values.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class KeyScreensScreenshotTest {

    companion object {
        // Explicit rather than relying on the roborazzi { outputDir.set(...) }
        // Gradle extension alone: that extension only auto-prefixes captureRoboImage()
        // calls that *omit* a filePath (letting Roborazzi derive one from the
        // test class/method name); an explicit filePath like the ones below is
        // resolved directly against the test JVM's working directory (the app
        // module root), so this project's committed-goldens directory is spelled
        // out here to match `outputDir` instead of depending on it.
        private const val GOLDEN_DIR = "src/test/screenshot/goldens/"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        // See the class doc -- neutralizes AppSecretsStore's BuildConfig fallbacks
        // so goldens don't encode whichever machine happens to run this test.
        app.secretsStore.isSignedOut = true
        app.secretsStore.selectedVaultOwner = "dobromirmontauk"
        app.secretsStore.selectedVaultRepo = "voice-vault"
    }

    @Test
    fun sessionsListWithNav() {
        // Distinct startedAt per session (an hour apart) rather than all three
        // sharing SessionFixtures.FIXED_STARTED_AT: SessionStore.listSessions()
        // sorts descending by startedAt, and with equal keys that sort falls
        // through to whatever order File.listFiles() happens to return --
        // filesystem- and therefore platform-dependent (this is exactly how a
        // macOS-recorded golden ended up with a different row order than the
        // one Linux CI rendered the first time this suite ran there).
        SessionFixtures.seedSession(
            app.sessionStore,
            sessionId = "2026-08-01_0900_ab12",
            transcriptText = "Kitchen remodel budget check",
            uploadState = UploadState.UPLOADED,
            startedAt = Date(SessionFixtures.FIXED_STARTED_AT.time),
        )
        SessionFixtures.seedSession(
            app.sessionStore,
            sessionId = "2026-08-01_1000_cd34",
            transcriptText = "Marathon training recap",
            uploadState = UploadState.QUEUED,
            startedAt = Date(SessionFixtures.FIXED_STARTED_AT.time + 3_600_000L),
        )
        SessionFixtures.seedSession(
            app.sessionStore,
            sessionId = "2026-08-01_1100_ef56",
            transcriptText = "Deck repair notes",
            uploadState = UploadState.LOCAL,
            startedAt = Date(SessionFixtures.FIXED_STARTED_AT.time + 7_200_000L),
        )

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SESSIONS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "sessions_with_nav.png")
    }

    @Test
    fun recordingFullStack() {
        RecordingStateHolder.update {
            it.copy(isRecording = true, sessionId = "2026-08-01_1200_gh78", elapsedMs = 125_000L, mode = RecordingMode.LISTEN)
        }
        TranscriptStateHolder.update {
            TranscriptUiState(
                connectionState = SttConnectionState.CONNECTED,
                finalLines = listOf(
                    TranscriptLine("So the budget for the kitchen remodel is around twelve thousand.", 0L, 4_000L),
                    TranscriptLine("We still need a quote from the electrician before signing off.", 4_000L, 8_000L),
                ),
                currentPartial = "And the tile is coming in next",
                micLevel = 0.4f,
                silenceHintVisible = false,
                sourceLabel = "FILE",
            )
        }
        // Bead vn-edu.38: tag chips no longer derive synchronously from
        // TranscriptStateHolder inside the composable (the way the old
        // TopicCloud-based chips did) -- they come from TagsStateHolder,
        // which only RecordingService's TagCoordinator pipeline populates.
        // No such pipeline runs in this Robolectric render, so this golden
        // seeds fixed tags directly, matching the transcript fixture above.
        TagsStateHolder.update(
            listOf(
                DisplayedTag("kitchen remodel", 0.92, rank = 1, tier = TagTier.PRIMARY),
                DisplayedTag("budget", 0.7, rank = 2, tier = TagTier.SECONDARY),
            ),
        )

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "recording_full_stack.png")
    }

    @Test
    fun sessionDetail() {
        SessionFixtures.seedSession(
            app.sessionStore,
            sessionId = "2026-08-01_0900_ab12",
            transcriptText = "Kitchen remodel budget check",
            uploadState = UploadState.UPLOADED,
        )

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.sessionDetail("2026-08-01_0900_ab12"), onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "session_detail.png")
    }

    @Test
    fun settings() {
        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.SETTINGS, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "settings.png")
    }

    /**
     * Residual, *documented* config-dependence this test can't close off the
     * same way [setUp] does for [settings]: [com.montauk.voicecapture.ui.LoginScreen]
     * reads `VoiceCaptureApp.isGithubOAuthConfigured()`, which checks
     * `BuildConfig.GITHUB_OAUTH_CLIENT_ID` directly with no `AppSecretsStore`
     * seam to override at test runtime -- it's a per-build-type constant, not
     * a SharedPreferences value. Safe today because setting it up requires
     * manually registering a GitHub OAuth App (see `app/build.gradle.kts`'s
     * `github.oauthClientId` comment); confirmed unset in both the keyless
     * and keyed `local.properties` this suite was verified against. If that
     * ever changes on some machine/CI, this golden -- and only this one --
     * would need re-verifying across environments.
     */
    @Test
    fun login() {
        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.LOGIN, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "login.png")
    }
}
