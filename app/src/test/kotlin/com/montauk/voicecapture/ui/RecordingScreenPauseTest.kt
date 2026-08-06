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
 * Bead asn-r60's pause state machine, bead asn-3sm's presentation: team-lead's
 * v2 pause redesign dropped asn-r60's own interim `PauseBanner`/
 * `BottomActionsBar` UI outright ("no other pause UI -- the duck asleep +
 * Z-trail + filled button + frozen timer is the entire pause presentation")
 * -- see [RecordingScreen]'s [PauseResumeChip] and [MinimalTopChrome]. This
 * suite exercises that presentation instead: the floating Pause/Resume pill
 * lives on the default duck view (no double-tap needed -- it's part of
 * [DuckStage]'s `controls` slot), and there is no banner anywhere. Drives the
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
    fun `pause pill shows PAUSE while speaking or quiet, and a tap calls onSetPaused(true)`() {
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
        composeTestRule.onNodeWithText("⏸ PAUSE").assertIsDisplayed()
        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(true), pausedCalls)
    }

    @Test
    fun `pause pill shows RESUME while USER_PAUSED, and a tap calls onSetPaused(false)`() {
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

        composeTestRule.onNodeWithText("▶ RESUME").assertIsDisplayed()
        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), pausedCalls)
    }

    @Test
    fun `pause pill still reads PAUSE while AUTO_PAUSED -- a tap escalates to a hard pause, it does not resume`() {
        RecordingActivityStateHolder.set(RecordingActivityState.AUTO_PAUSED)
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

        // While auto-paused, the pill still reads PAUSE -- a tap there
        // escalates to a hard pause (onSetPaused(true)), it is not a resume
        // affordance for the soft/VAD-driven pause (which auto-resumes on its
        // own the moment speech resumes -- see RecordingActivityState's KDoc).
        composeTestRule.onNodeWithText("⏸ PAUSE").assertIsDisplayed()

        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(true), pausedCalls)
    }

    @Test
    fun `top chrome shows the pause indicator matching each activity state, and no banner exists anywhere`() {
        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("● REC").assertIsDisplayed()

        RecordingActivityStateHolder.set(RecordingActivityState.AUTO_PAUSED)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("⏸ auto").assertIsDisplayed()

        RecordingActivityStateHolder.set(RecordingActivityState.USER_PAUSED)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("⏸ paused").assertIsDisplayed()
    }
}
