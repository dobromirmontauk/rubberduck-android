package com.montauk.voicecapture.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.duck.DUCK_ANIMATOR_TEST_TAG
import com.montauk.voicecapture.duck.DUCK_STAGE_TEST_TAG
import com.montauk.voicecapture.duck.PROPOSED_WORD_TEST_TAG_PREFIX
import com.montauk.voicecapture.service.RecordingActivityStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagRailStateHolder
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.tags.RailChipSource
import com.montauk.voicecapture.tags.TagRailChip
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bead asn-bb4.1/asn-bb4.2/asn-bb4.7: geometric assertions against the REAL
 * [RecordingScreen] composition (via [AppNavHost], same as the Roborazzi
 * "full-stack actual" gate renders) -- not a synthetic isolated harness.
 * This is deliberate: the screen-vs-storyboard gate's original finding was
 * that an isolated [com.montauk.voicecapture.duck.DuckStage] render looked
 * fine while the real screen (extra chrome, the keyless message) did not,
 * so these tests exercise the exact same composition the app ships.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class RecordingScreenDuckLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: VoiceCaptureApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        TagRailStateHolder.reset()
        RecordingActivityStateHolder.reset()
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = "2026-08-07_0900_layout", mode = RecordingMode.LISTEN) }
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

    /**
     * Bead asn-bb4.1, USER DECISION 2026-08-07 (supersedes an earlier
     * 62-70% target): the duck stays at max width with no side-cropping,
     * which on a tall phone lands his head apex at ~50-55% of full screen
     * height -- that is the accepted spec, not the storyboard mock's own
     * stubbier aspect ratio. Also asserts the "no crop" half: the animator's
     * own width equals the duck stage's full width (edge to edge), not a
     * narrower box that would have implied side-cropping.
     */
    @Test
    fun `duck head apex lands at 50-55 percent of screen height, full width, no crop`() {
        RecordingActivityStateHolder.set(com.montauk.voicecapture.service.RecordingActivityState.SPEAKING)
        renderDuckView()

        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val screenHeight = root.bottom - root.top
        val stage = composeTestRule.onNodeWithTag(DUCK_STAGE_TEST_TAG).getUnclippedBoundsInRoot()
        val animator = composeTestRule.onNodeWithTag(DUCK_ANIMATOR_TEST_TAG).getUnclippedBoundsInRoot()

        val headApexFractionFromTop = (animator.top - root.top) / screenHeight
        val headApexFractionUpFromBottom = 1f - headApexFractionFromTop
        assertTrue(
            "head apex should be 50-55% up from the bottom of the screen, was ${headApexFractionUpFromBottom * 100}%",
            headApexFractionUpFromBottom in 0.50f..0.55f,
        )

        // "No crop": the animator spans the SAME full width as the stage
        // (edge to edge) -- ContentScale.Fit never crops the sprite itself,
        // but a narrower box here would mean the duck isn't shown at his
        // full available width.
        assertTrue("animator should span the stage's full width", animator.left == stage.left && animator.right == stage.right)
    }

    /**
     * Bead asn-bb4.2: the pause/resume pill and STOP must OVERLAP the duck
     * stage's lower region (their vertical ranges intersect the animator's
     * own bounds), not sit stacked in a row entirely below him.
     */
    @Test
    fun `pause and stop controls vertically overlap the duck animator, not stacked below him`() {
        RecordingActivityStateHolder.set(com.montauk.voicecapture.service.RecordingActivityState.SPEAKING)
        renderDuckView()

        val animator = composeTestRule.onNodeWithTag(DUCK_ANIMATOR_TEST_TAG).getUnclippedBoundsInRoot()
        val pause = composeTestRule.onNodeWithTag(PAUSE_RESUME_BUTTON_TEST_TAG).getUnclippedBoundsInRoot()
        val stop = composeTestRule.onNodeWithText("STOP").getUnclippedBoundsInRoot()

        // Overlap means the control's top edge is ABOVE the animator's
        // bottom edge (they share some vertical range) -- a control
        // "stacked below" would have its whole box under the animator's
        // bottom edge instead.
        assertTrue("pause pill (top=${pause.top}) should overlap the duck's lower body (animator bottom=${animator.bottom})", pause.top < animator.bottom)
        assertTrue("stop pill (top=${stop.top}) should overlap the duck's lower body (animator bottom=${animator.bottom})", stop.top < animator.bottom)
    }

    /**
     * Bead asn-bb4.7: with a real Anthropic key configured (so the word
     * cloud actually renders -- keyless shows the register-key message
     * instead, covered elsewhere), the top word-cloud slot sits ABOVE the
     * duck's own head apex and below the timer row, i.e. it reads as
     * "around/above his head," not overlapping the timer chrome or hidden
     * below his head.
     */
    @Test
    fun `top word-cloud slot sits above the duck's head and below the timer row`() {
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAnthropicKey = "sk-ant-configured-test-key"
        // "gardening": no tagId (not EXISTING) and source != USER (not
        // APPROVED) -- resolves to TagWordStatus.PROPOSED, matching
        // PROPOSED_WORD_TEST_TAG_PREFIX below (see TagRailChip.wordStatus).
        TagRailStateHolder.update(listOf(TagRailChip("gardening", source = RailChipSource.SUGGESTED)))
        RecordingActivityStateHolder.set(com.montauk.voicecapture.service.RecordingActivityState.SPEAKING)
        renderDuckView()

        val animator = composeTestRule.onNodeWithTag(DUCK_ANIMATOR_TEST_TAG).getUnclippedBoundsInRoot()
        val timer = composeTestRule.onNodeWithText("00:00").getUnclippedBoundsInRoot()
        val word = composeTestRule.onNodeWithTag(PROPOSED_WORD_TEST_TAG_PREFIX + "gardening", useUnmergedTree = true).getUnclippedBoundsInRoot()

        assertTrue("word (top=${word.top}) should be below the timer row (bottom=${timer.bottom})", word.top >= timer.bottom)
        assertTrue("word (top=${word.top}) should sit above the duck's head apex (animator top=${animator.top})", word.top < animator.top)
    }
}
