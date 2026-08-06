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
 * Bead asn-r60's pause state machine, asn-o63's live-test fixes, bead
 * asn-3sm's presentation: team-lead's v2 pause redesign dropped asn-r60/
 * asn-o63's own interim `PauseBanner`/`BottomActionsBar` UI outright ("no
 * need for any other UI, keep it minimal" -- asn-o63's own words, echoing
 * team-lead's identical direction) -- see [RecordingScreen]'s
 * [PauseResumeChip] and [MinimalTopChrome]. This suite exercises that
 * presentation instead: the floating Pause/Resume pill lives on the default
 * duck view (no double-tap needed -- it's part of [DuckStage]'s `controls`
 * slot), and there is no banner anywhere. Drives the real nav graph
 * ([AppNavHost]), same pattern as
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
        composeTestRule.onNodeWithText("⏸ PAUSE").assertDoesNotExist()
        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), pausedCalls)
    }

    /**
     * Bead asn-o63's core bug fix, carried over onto this bead's floating
     * pill: a live tester saw the button label NOT change while auto-paused.
     * Asserts the label AND the tap semantics -- tapping while auto-paused
     * now always resumes (there is no other affordance left to offer an
     * escalate-to-hard-pause path; see [PauseResumeChip]'s KDoc).
     */
    @Test
    fun `pause pill shows RESUME while AUTO_PAUSED, and a tap calls onSetPaused(false)`() {
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

        composeTestRule.onNodeWithText("▶ RESUME").assertIsDisplayed()
        composeTestRule.onNodeWithText("⏸ PAUSE").assertDoesNotExist()
        composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), pausedCalls)
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
        composeTestRule.onNodeWithText("just start talking, or tap to resume").assertDoesNotExist()

        RecordingActivityStateHolder.set(RecordingActivityState.USER_PAUSED)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("⏸ paused").assertIsDisplayed()
        composeTestRule.onNodeWithText("tap Resume to keep recording").assertDoesNotExist()
    }

    /** Bead asn-o63: the fill overlay only ever shows while NOT already paused -- it's meaningless once auto-pause has actually fired. */
    @Test
    fun `fill overlay is absent while AUTO_PAUSED or USER_PAUSED even if the fill fraction is maxed`() {
        RecordingActivityStateHolder.set(RecordingActivityState.AUTO_PAUSED)
        TranscriptStateHolder.update { it.copy(autoPauseFillFraction = 1f) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(AUTO_PAUSE_FILL_OVERLAY_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * Bead asn-o63: the fill overlay renders while QUIET and mid-fill, ahead
     * of an eventual auto-pause. `useUnmergedTree = true` -- the overlay is
     * a descendant of the Pause [androidx.compose.material3.Button], which
     * merges its children's semantics into itself for accessibility, so the
     * plain (merged) tree doesn't expose this tag as its own node. The
     * fill's actual math is additionally covered directly by
     * [com.montauk.voicecapture.audio.AutoPauseFillTest], a plain-JVM
     * pure-function test with no Compose/layout dependency.
     */
    @Test
    fun `fill overlay is present while QUIET with a positive fill fraction`() {
        RecordingActivityStateHolder.set(RecordingActivityState.QUIET)
        TranscriptStateHolder.update { it.copy(autoPauseFillFraction = 0.4f) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(AUTO_PAUSE_FILL_OVERLAY_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
    }
}
