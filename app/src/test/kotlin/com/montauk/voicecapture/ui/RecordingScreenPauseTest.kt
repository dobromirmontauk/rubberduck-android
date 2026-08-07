package com.montauk.voicecapture.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.duck.DUCK_STAGE_TEST_TAG
import com.montauk.voicecapture.service.RecordingActivityState
import com.montauk.voicecapture.service.RecordingActivityStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Bead v5.1: the "● REC"/"⏸ auto"/"⏸ paused" top-chrome labels are gone
     * from the duck view in every state -- the sleeping duck, the Z-trail,
     * the frozen big timer, and the floating Resume pill already say
     * everything there is to say (see [MinimalTopChrome]'s KDoc). This
     * replaces the old version of this test, which asserted the OPPOSITE
     * (that these labels were displayed) -- that was true before v5.1
     * dropped them from the duck view specifically.
     */
    @Test
    fun `duck view shows no REC or auto-pause top-chrome labels in any activity state`() {
        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("● REC").assertDoesNotExist()

        RecordingActivityStateHolder.set(RecordingActivityState.AUTO_PAUSED)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("⏸ auto").assertDoesNotExist()
        composeTestRule.onNodeWithText("just start talking, or tap to resume").assertDoesNotExist()

        RecordingActivityStateHolder.set(RecordingActivityState.USER_PAUSED)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("⏸ paused").assertDoesNotExist()
        composeTestRule.onNodeWithText("tap Resume to keep recording").assertDoesNotExist()
    }

    /**
     * Bead v5.1 exempts only the duck view -- the debug/transcript view
     * (reached via the same double-tap toggle [RecordingScreenDuckToggleTest]
     * covers) keeps its top-chrome activity indicator exactly as before.
     */
    @Test
    fun `debug view still shows the top-chrome activity indicator`() {
        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING)

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(DUCK_TRANSCRIPT_TOGGLE_TEST_TAG).performTouchInput { doubleClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("● REC").assertIsDisplayed()
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

    /**
     * Bead asn-52b: the gradient fill must be invisible at fraction 0, a
     * partial fill at 0.5, and its widest extent at 1 -- checked here by the
     * overlay's own rendered width, not just presence/absence. Compares the
     * half-fill width against the full-fill width (roughly half), rather
     * than against the pause button's own outer width, because
     * [PauseResumeChip] deliberately insets the fill by the button's
     * `contentPadding` (see its KDoc) -- the button's outer width is not the
     * fillable area's width. The fill never exceeding the button's own
     * bounds at all is covered separately (and is asn-myi's whole point) by
     * `fill overlay stays within the pause button bounds and siblings are
     * unchanged`, below.
     */
    @Test
    fun `fill overlay width tracks fillFraction from invisible at 0 to widest at 1`() {
        RecordingActivityStateHolder.set(RecordingActivityState.QUIET)
        TranscriptStateHolder.update { it.copy(autoPauseFillFraction = 0f) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        // fraction 0f: AutoPauseFillOverlay returns early -- no overlay node at all.
        composeTestRule.onNodeWithTag(AUTO_PAUSE_FILL_OVERLAY_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()

        val buttonBounds = composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).getUnclippedBoundsInRoot()
        val buttonWidth = buttonBounds.right - buttonBounds.left

        TranscriptStateHolder.update { it.copy(autoPauseFillFraction = 0.5f) }
        composeTestRule.waitForIdle()
        val halfBounds = composeTestRule
            .onNodeWithTag(AUTO_PAUSE_FILL_OVERLAY_TEST_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val halfWidth = halfBounds.right - halfBounds.left

        TranscriptStateHolder.update { it.copy(autoPauseFillFraction = 1f) }
        composeTestRule.waitForIdle()
        val fullBounds = composeTestRule
            .onNodeWithTag(AUTO_PAUSE_FILL_OVERLAY_TEST_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val fullWidth = fullBounds.right - fullBounds.left

        assertTrue("full-fill width ($fullWidth) should exceed half-fill width ($halfWidth)", fullWidth > halfWidth)
        val ratio = halfWidth / fullWidth
        assertTrue("half/full width ratio ($ratio) should be roughly 0.5", ratio in 0.3f..0.7f)
        assertTrue("full-fill width ($fullWidth) must never exceed the button's own width ($buttonWidth)", fullWidth <= buttonWidth)
    }

    /**
     * Regression test for asn-myi: a live tester saw the fill overlay render
     * as a giant ellipse covering ~90% of the screen instead of staying
     * inside the pause pill. Root cause was [AutoPauseFillOverlay]'s
     * `Modifier.fillMaxSize()` -- inside a Button/Row/Box chain that doesn't
     * clamp its own max-width constraints, a `fillMaxSize()` descendant
     * pulls the *ancestor* pill outward to whatever loose max constraints
     * happen to be available (here, the whole duck stage), rather than
     * being confined to the pill's own wrap-content size. The fix (asn-52b)
     * uses `BoxScope.matchParentSize()` instead, which is measured last and
     * sized to match the Box's already-resolved (Text-driven) size, so the
     * overlay can never inflate its own container. Asserts both halves of
     * asn-myi's acceptance criteria: the overlay's bounds stay within the
     * pause pill's own bounds, and the button's own footprint plus its
     * siblings (duck stage, STOP) are unaffected by the fill fraction.
     */
    @Test
    fun `fill overlay stays within the pause button bounds and siblings are unchanged`() {
        RecordingActivityStateHolder.set(RecordingActivityState.QUIET)
        TranscriptStateHolder.update { it.copy(autoPauseFillFraction = 0f) }

        composeTestRule.setContent {
            AppNavHost(startDestination = Routes.RECORDING, onNewSessionTapped = {}, onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        val buttonBoundsAtZero = composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).getUnclippedBoundsInRoot()
        val duckBoundsAtZero = composeTestRule.onNodeWithTag(DUCK_STAGE_TEST_TAG).getUnclippedBoundsInRoot()
        val stopBoundsAtZero = composeTestRule.onNodeWithText("STOP").getUnclippedBoundsInRoot()

        TranscriptStateHolder.update { it.copy(autoPauseFillFraction = 0.5f) }
        composeTestRule.waitForIdle()

        val buttonBounds = composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).getUnclippedBoundsInRoot()
        val overlayBounds = composeTestRule
            .onNodeWithTag(AUTO_PAUSE_FILL_OVERLAY_TEST_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        // The pause button itself, and everything else on screen, must be
        // completely unaffected by the mid-fill fraction.
        assertEquals(buttonBoundsAtZero, buttonBounds)
        assertEquals(duckBoundsAtZero, composeTestRule.onNodeWithTag(DUCK_STAGE_TEST_TAG).getUnclippedBoundsInRoot())
        assertEquals(stopBoundsAtZero, composeTestRule.onNodeWithText("STOP").getUnclippedBoundsInRoot())

        // The fill overlay must render fully inside the pause pill's own
        // bounds -- never wider/taller than the button, never outside it.
        assertTrue("overlay left (${overlayBounds.left}) < button left (${buttonBounds.left})", overlayBounds.left >= buttonBounds.left)
        assertTrue("overlay top (${overlayBounds.top}) < button top (${buttonBounds.top})", overlayBounds.top >= buttonBounds.top)
        assertTrue("overlay right (${overlayBounds.right}) > button right (${buttonBounds.right})", overlayBounds.right <= buttonBounds.right)
        assertTrue("overlay bottom (${overlayBounds.bottom}) > button bottom (${buttonBounds.bottom})", overlayBounds.bottom <= buttonBounds.bottom)

        // Sanity ceiling matching asn-myi's screenshot bug (a fill that
        // covered ~90% of the screen): the overlay must be much smaller
        // than the duck stage, not comparable to it.
        assertTrue((overlayBounds.right - overlayBounds.left) < (duckBoundsAtZero.right - duckBoundsAtZero.left) / 2)
        assertTrue((overlayBounds.bottom - overlayBounds.top) < (duckBoundsAtZero.bottom - duckBoundsAtZero.top) / 2)
    }
}
