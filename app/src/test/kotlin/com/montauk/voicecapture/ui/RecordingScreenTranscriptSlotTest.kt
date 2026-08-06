package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptLine
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.stt.SttConnectionState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.66: with no effective AssemblyAI key, the recording screen's
 * live-transcript pane must not imply transcription is happening ("Listening…"
 * or a "(silence)" hint) -- it shows the exact string "(no transcription
 * key)", dimmed and tappable, and tapping it deep-links to Settings' "Live
 * transcription" key row. Mirrors [RecordingScreenTagsSlotTest] (bead
 * vn-edu.46)'s pattern exactly, keyed off [VoiceCaptureApp.isAssemblyKeyConfigured]
 * instead of the Anthropic key. Drives the real nav graph ([AppNavHost])
 * rather than [RecordingScreen] in isolation so the deep link's destination
 * is actually verified.
 *
 * Bead asn-45m: pinned to [RobolectricDeviceQualifiers.Pixel7] (matching
 * [LiveTranscriptPaneWordFinalityTest]/[LiveTranscriptPaneOverlongPartialTest],
 * which exercise this same pane) rather than Robolectric's unqualified
 * default window -- the new tag rail + filing ribbon above the transcript
 * pane push its `weight(1f)` allotment to zero height on that tiny default,
 * which no real device this app ships on is anywhere close to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class RecordingScreenTranscriptSlotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        // isSignedOut = true forces every effective*() getter (including
        // effectiveAssemblyKey) to blank regardless of what BuildConfig was
        // compiled with -- a machine whose local.properties carries a real
        // assemblyai.apiKey would otherwise make "keyless" tests here
        // spuriously see a configured key. userAssemblyAiKey = null on top
        // is redundant with isSignedOut alone but kept for clarity/intent.
        app.secretsStore.isSignedOut = true
        app.secretsStore.userAssemblyAiKey = null
    }

    @Test
    fun `keyless shows the exact no-transcription-key message, not Listening`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(no transcription key)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening…").assertDoesNotExist()
    }

    @Test
    fun `keyless with transcript state somehow populated still shows the message, never a real line`() {
        // Defensive: even if TranscriptStateHolder is stale/non-empty (shouldn't
        // happen with NoOpStreamingSttClient, which never emits a partial or
        // final), this proves the UI gate is on the key, not merely on
        // "is the transcript empty".
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TranscriptStateHolder.update {
            it.copy(
                connectionState = SttConnectionState.CONNECTED,
                finalLines = listOf(TranscriptLine("stale transcript line", startMs = 0L, endMs = 900L)),
            )
        }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(no transcription key)").assertIsDisplayed()
        composeTestRule.onNodeWithText("stale transcript line").assertDoesNotExist()
    }

    @Test
    fun `tapping the no-transcription-key message deep-links to Settings' Live transcription key row`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(no transcription key)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ASSEMBLYAI_KEY_ROW_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Live transcription").assertIsDisplayed()
    }

    @Test
    fun `a configured AssemblyAI key shows the normal Listening placeholder, not the keyless message`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAssemblyAiKey = "assemblyai-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Listening…").assertIsDisplayed()
        composeTestRule.onNodeWithText("(no transcription key)").assertDoesNotExist()
    }

    @Test
    fun `a configured AssemblyAI key with a real final line renders it, not the keyless message`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAssemblyAiKey = "assemblyai-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TranscriptStateHolder.update {
            it.copy(
                connectionState = SttConnectionState.CONNECTED,
                finalLines = listOf(TranscriptLine("kitchen remodel budget update", startMs = 0L, endMs = 900L)),
            )
        }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("kitchen remodel budget update").assertIsDisplayed()
        composeTestRule.onNodeWithText("(no transcription key)").assertDoesNotExist()
    }
}
