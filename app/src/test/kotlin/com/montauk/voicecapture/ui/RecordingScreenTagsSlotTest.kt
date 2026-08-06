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
import com.montauk.voicecapture.service.TagRailStateHolder
import com.montauk.voicecapture.service.TagTreeStateHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.tags.RailChipSource
import com.montauk.voicecapture.tags.TagRailChip
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.46 superseding decision (2026-08-05), still in force after
 * bead asn-45m replaced the read-only tags slot with the editable tag rail
 * (see [RecordingScreenTagRailTest] for the rail/picker's own interaction
 * coverage): keyless with nothing user-added yet must show the exact
 * register-key message, never an empty row and never a heuristic guess, and
 * tapping it must deep-link to Settings' "Word cloud & titles" key row.
 * Drives the real nav graph ([AppNavHost]) rather than [RecordingScreen] in
 * isolation so the deep link's destination is actually verified, same
 * pattern as [AppNavHostInteractionTest].
 *
 * Pinned to [RobolectricDeviceQualifiers.Pixel7] -- see
 * [RecordingScreenTranscriptSlotTest]'s KDoc for why the unqualified
 * Robolectric default window is too small once the tag rail sits above the
 * transcript pane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class RecordingScreenTagsSlotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TagRailStateHolder.reset()
        TagTreeStateHolder.reset()
        // isSignedOut = true forces every effective*() getter (including
        // effectiveAnthropicKey) to blank regardless of what BuildConfig was
        // compiled with -- a machine whose local.properties carries a real
        // anthropic.apiKey would otherwise make "keyless" tests here
        // spuriously see a configured key. userAnthropicKey = null on top is
        // redundant with isSignedOut alone but kept for clarity/intent.
        app.secretsStore.isSignedOut = true
        app.secretsStore.userAnthropicKey = null
    }

    @Test
    fun `keyless shows the exact register-key message, not an empty row or chips`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertIsDisplayed()
    }

    @Test
    fun `tapping the register-key message deep-links to Settings' Anthropic key row`() {
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ANTHROPIC_KEY_ROW_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Word cloud & titles").assertIsDisplayed()
    }

    @Test
    fun `a configured Anthropic key renders real tag rail chips, not the register-key message`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
        TagRailStateHolder.update(listOf(TagRailChip("kitchen remodel", source = RailChipSource.SUGGESTED)))

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("kitchen remodel").assertIsDisplayed()
        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
    }

    @Test
    fun `a configured Anthropic key with no tags yet shows neither the message nor a stray chip, and STOP still renders`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("(register your API key to see the word cloud)").assertDoesNotExist()
        composeTestRule.onNodeWithText("STOP").assertIsDisplayed()
    }
}
