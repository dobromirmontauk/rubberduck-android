package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingActivityState
import com.montauk.voicecapture.service.RecordingActivityStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead asn-r60: the pause button next to Stop, and the auto-pause banner --
 * see [RecordingScreen]'s `PauseBanner`/`BottomActionsBar` KDoc. Drives the
 * real nav graph ([AppNavHost]), same pattern as
 * [RecordingScreenTagsSlotTest]/[RecordingScreenTranscriptSlotTest], since
 * `onSetPaused` threads through it to [RecordingScreen].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingScreenPauseTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        RecordingActivityStateHolder.reset()
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-01_0900_ab12", mode = RecordingMode.LISTEN) }
    }

    @After
    fun tearDown() {
        // Bead asn-r60: a global singleton (same pattern as RecordingStateHolder/
        // TranscriptStateHolder) -- reset it back to the default so a later
        // test class in the same test JVM never inherits a paused state this
        // one set.
        RecordingActivityStateHolder.reset()
    }

    @Test
    fun `pause button shows PAUSE while speaking or quiet, and a tap calls onSetPaused(true)`() {
        RecordingActivityStateHolder.set(RecordingActivityState.QUIET)
        val pausedCalls = mutableListOf<Boolean>()

        composeTestRule.setContent {
            AppNavHost(
                startDestination = Routes.RECORDING,
                onNewSessionTapped = {},
                onStopRecording = {},
                onSetPaused = { pausedCalls.add(it) },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("PAUSE").assertIsDisplayed()
        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).performClick()

        assertEquals(listOf(true), pausedCalls)
    }

    @Test
    fun `pause button shows RESUME and the manual-paused banner while USER_PAUSED, and a tap calls onSetPaused(false)`() {
        RecordingActivityStateHolder.set(RecordingActivityState.USER_PAUSED)
        val pausedCalls = mutableListOf<Boolean>()

        composeTestRule.setContent {
            AppNavHost(
                startDestination = Routes.RECORDING,
                onNewSessionTapped = {},
                onStopRecording = {},
                onSetPaused = { pausedCalls.add(it) },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("RESUME").assertIsDisplayed()
        composeTestRule.onNodeWithTag(USER_PAUSE_BANNER_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).performClick()

        assertEquals(listOf(false), pausedCalls)
    }

    @Test
    fun `auto-pause banner shows the exact wording and quiet duration, and tapping it calls onSetPaused(false)`() {
        RecordingActivityStateHolder.set(RecordingActivityState.AUTO_PAUSED)
        TranscriptStateHolder.update { it.copy(quietDurationMs = 32_000L) }
        val pausedCalls = mutableListOf<Boolean>()

        composeTestRule.setContent {
            AppNavHost(
                startDestination = Routes.RECORDING,
                onNewSessionTapped = {},
                onStopRecording = {},
                onSetPaused = { pausedCalls.add(it) },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Auto-paused (quiet 0:32) -- just start talking, or tap to resume").assertIsDisplayed()
        // While auto-paused, the bottom pause button still reads PAUSE (a tap
        // there would escalate to a hard pause, not resume) -- only the
        // banner itself offers "tap to resume" for the soft pause.
        composeTestRule.onNodeWithText("PAUSE").assertIsDisplayed()

        composeTestRule.onNodeWithTag(AUTO_PAUSE_BANNER_TEST_TAG).performClick()

        assertEquals(listOf(false), pausedCalls)
    }

    @Test
    fun `no pause banner at all while speaking or quiet`() {
        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(AUTO_PAUSE_BANNER_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(USER_PAUSE_BANNER_TEST_TAG).assertDoesNotExist()
    }
}
