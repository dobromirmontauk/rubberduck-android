package com.montauk.voicecapture.ui

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.stt.SttConnectionState
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Failing-first repro for bead vn-edu.43: the user's screenshots (2026-08-05
 * 14:52) showed a single continuously-growing PARTIAL (no turn finalized for
 * ~45-60s) rendered as one [LazyColumn][androidx.compose.foundation.lazy.LazyColumn]
 * item taller than the pane's viewport. The pane's old
 * `animateScrollToItem(lastIndex)` pins that item's TOP to the viewport's
 * top, so the item's BOTTOM -- where the newest words live, since [Text]
 * lays a paragraph out top-to-bottom -- extends past the visible area and
 * stays permanently clipped. The screen reads as frozen even though new
 * words keep arriving.
 *
 * This test drives the real [RecordingScreen] (same entry point production
 * code renders) with a 700-word single partial and no finals -- the exact
 * shape of the bug -- and checks the geometry via
 * [androidx.compose.ui.test.getUnclippedBoundsInRoot], not plain
 * `fetchSemanticsNode().boundsInRoot`: an earlier version of this test used
 * the latter and was a false negative in both directions (it passed against
 * both the buggy and the fixed pane) -- Compose reports a scrolled item's
 * *clipped* semantics bounds to match accessibility conventions, so a
 * `boundsInRoot` comparison always sees a viewport-sized rectangle for the
 * item regardless of which end of its actual (much taller) content that
 * rectangle is currently showing. `getUnclippedBoundsInRoot` is the test
 * framework's own escape hatch for exactly this: it reports the node's true,
 * un-clipped layout position, so the item's real top/bottom edges (and thus
 * which one is pinned) are visible to the assertion below.
 *
 * [GraphicsMode.Mode.NATIVE] is required here (same as
 * [com.montauk.voicecapture.screenshot.KeyScreensScreenshotTest]) -- without
 * it Robolectric's default text-measurement shadow never wraps text onto
 * multiple lines regardless of width, so a 700-word partial would never
 * actually overflow the viewport in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveTranscriptPaneOverlongPartialTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        // Bead asn-3sm: RecordingScreen now also reads these three
        // singletons -- reset so an earlier test class's state (approvals,
        // a fake summary, a latency badge) never leaks into this one.
        com.montauk.voicecapture.service.TagApprovalStateHolder.reset()
        com.montauk.voicecapture.service.SummaryStateHolder.reset()
        com.montauk.voicecapture.service.LatencyBadgeStateHolder.reset()
        com.montauk.voicecapture.service.TagRailStateHolder.reset()
        com.montauk.voicecapture.service.TagTreeStateHolder.reset()
        com.montauk.voicecapture.service.RecordingActivityStateHolder.reset()
        // Bead vn-edu.66: the pane now gates on the effective AssemblyAI key
        // -- this test's fixture implies a real CONNECTED session (it seeds
        // currentPartial directly), so it needs a configured key or it would
        // hit the new keyless message instead of the pane under test here.
        val app: VoiceCaptureApp = ApplicationProvider.getApplicationContext()
        // isSignedOut = false is explicit, not assumed -- see the identical
        // comment in LiveTranscriptPaneWordFinalityTest.setUp().
        app.secretsStore.isSignedOut = false
        app.secretsStore.userAssemblyAiKey = "assemblyai-configured-test-key"
    }

    @Test
    fun newestWordsOfAGrowingPartialStayVisibleWhenTallerThanTheViewport() {
        val words = (1..700).joinToString(" ") { i -> "word$i" }
        TranscriptStateHolder.update {
            it.copy(connectionState = SttConnectionState.CONNECTED, currentPartial = words, sourceLabel = "FILE")
        }

        composeTestRule.setContent {
            RecordingScreen(onStopRecording = {})
        }
        composeTestRule.waitForIdle()
        // Bead asn-3sm: the transcript pane now lives behind the duck view's
        // double-tap debug toggle rather than being always-visible.
        composeTestRule.onNodeWithTag(DUCK_TRANSCRIPT_TOGGLE_TEST_TAG).performTouchInput { doubleClick() }
        composeTestRule.waitForIdle()

        val paneBounds = composeTestRule.onNodeWithTag(LIVE_TRANSCRIPT_PANE_TEST_TAG).getUnclippedBoundsInRoot()
        val partialTextBounds = composeTestRule.onNodeWithText("word700", substring = true).getUnclippedBoundsInRoot()
        val paneHeight = paneBounds.bottom - paneBounds.top
        val partialTextHeight = partialTextBounds.bottom - partialTextBounds.top

        assertTrue(
            "expected the 700-word partial to actually overflow the pane's viewport (nothing to pin " +
                "otherwise) -- pane height=$paneHeight, partial height=$partialTextHeight",
            partialTextHeight > paneHeight,
        )
        assertTrue(
            "expected the partial's bottom edge (newest words, y=${partialTextBounds.bottom}) to sit within " +
                "the pane's visible viewport (bottom=${paneBounds.bottom}) instead of being clipped below it -- " +
                "the pane is pinning the TOP of the overlong item instead of its BOTTOM",
            partialTextBounds.bottom <= paneBounds.bottom + BOUNDS_TOLERANCE_DP,
        )
    }

    private companion object {
        val BOUNDS_TOLERANCE_DP = 1.dp
    }
}
