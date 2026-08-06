package com.montauk.voicecapture.ui

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.duck.DUCK_STAGE_TEST_TAG
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

/**
 * Bead asn-3sm: the duck view is the default; double-tapping
 * [DUCK_TRANSCRIPT_TOGGLE_TEST_TAG] swaps in the debug transcript view
 * ([LIVE_TRANSCRIPT_PANE_TEST_TAG]), and double-tapping again swaps back --
 * a real toggle, not a one-way reveal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingScreenDuckToggleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        // A configured AssemblyAI key (rather than the keyless default) so the
        // debug view renders LiveTranscriptPane's keyed LazyColumn --
        // LIVE_TRANSCRIPT_PANE_TEST_TAG only exists on that branch, not on the
        // keyless early-return Box+Text one -- this test is about the
        // duck/transcript TOGGLE, not about which transcript-pane variant renders.
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAssemblyAiKey = "assemblyai-configured-test-key"
    }

    private fun doubleTapToggle() {
        composeTestRule.onNodeWithTag(DUCK_TRANSCRIPT_TOGGLE_TEST_TAG).performTouchInput { doubleClick() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the duck view is the default on entering the recording screen`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(DUCK_STAGE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(LIVE_TRANSCRIPT_PANE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `double-tapping the duck reveals the debug transcript view`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        doubleTapToggle()

        composeTestRule.onNodeWithTag(LIVE_TRANSCRIPT_PANE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(DUCK_STAGE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `double-tapping again returns to the duck view`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        doubleTapToggle() // duck -> debug
        doubleTapToggle() // debug -> duck

        composeTestRule.onNodeWithTag(DUCK_STAGE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(LIVE_TRANSCRIPT_PANE_TEST_TAG).assertDoesNotExist()
    }
}
