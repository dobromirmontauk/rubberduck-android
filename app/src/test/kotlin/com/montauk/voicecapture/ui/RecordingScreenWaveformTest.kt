package com.montauk.voicecapture.ui

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingActivityState
import com.montauk.voicecapture.service.RecordingActivityStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.SummaryStateHolder
import com.montauk.voicecapture.service.TagRailStateHolder
import com.montauk.voicecapture.service.TagTreeStateHolder
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead asn-kd2, waveform spec v5.2: [DuckWaveformBar]'s three renderings,
 * keyed off [RecordingActivityState] alone -- see that composable's own
 * KDoc for the full spec this exercises. All three assert on the DUCK view
 * (not the debug view, which keeps its own separate, always-present
 * [LoudnessMeterBar] and never renders these test tags at all).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingScreenWaveformTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        SummaryStateHolder.reset()
        TagRailStateHolder.reset()
        TagTreeStateHolder.reset()
        RecordingActivityStateHolder.reset()
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-06_0900_wv01", mode = RecordingMode.LISTEN) }
    }

    @After
    fun tearDown() {
        RecordingActivityStateHolder.reset()
    }

    private fun renderDuckView() {
        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `SPEAKING shows the red live-recording waveform and nothing else`() {
        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING)
        renderDuckView()

        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_RECORDING_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_AUTO_PAUSED_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_FLAT_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(NOT_RECORDING_SIGN_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `QUIET (still recording, just VAD-quiet) also shows the red live-recording waveform`() {
        RecordingActivityStateHolder.set(RecordingActivityState.QUIET)
        renderDuckView()

        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_RECORDING_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_AUTO_PAUSED_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_FLAT_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `AUTO_PAUSED shows the grey still-moving waveform, not the flat one or the sign`() {
        RecordingActivityStateHolder.set(RecordingActivityState.AUTO_PAUSED)
        renderDuckView()

        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_AUTO_PAUSED_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_RECORDING_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_FLAT_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(NOT_RECORDING_SIGN_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `USER_PAUSED shows the flat waveform plus the NOT RECORDING sign, and nothing else`() {
        RecordingActivityStateHolder.set(RecordingActivityState.USER_PAUSED)
        renderDuckView()

        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_FLAT_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(NOT_RECORDING_SIGN_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_RECORDING_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_AUTO_PAUSED_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `switching to the debug view hides the duck-view waveform entirely`() {
        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING)
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAssemblyAiKey = "assemblyai-configured-test-key"
        renderDuckView()

        composeTestRule.onNodeWithTag(DUCK_TRANSCRIPT_TOGGLE_TEST_TAG).performTouchInput { doubleClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(DUCK_WAVEFORM_RECORDING_TEST_TAG).assertDoesNotExist()
    }
}
