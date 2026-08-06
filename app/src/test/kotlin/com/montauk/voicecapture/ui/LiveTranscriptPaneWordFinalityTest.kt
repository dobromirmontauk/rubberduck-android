package com.montauk.voicecapture.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.stt.SttConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Failing-first repro for bead vn-edu.45: the user asked "why does it stay
 * partial that long?" -- the pane rendered the *entire* open turn as one
 * uniformly-dimmed block until AssemblyAI's `end_of_turn`, which needs a
 * long pause and took 60s on continuous speech. But Universal-Streaming
 * words are immutable once AssemblyAI marks them `word_is_final` -- the fix
 * paints that stable prefix solid while the turn is still open, leaving only
 * the still-forming tail dimmed.
 *
 * This drives the real [RecordingScreen] with a partial that has a clear
 * stable/unstable split ([com.montauk.voicecapture.service.TranscriptStateHolder.partialStableText]/
 * `partialUnstableTail`) and inspects the rendered [Text]'s own
 * [androidx.compose.ui.text.AnnotatedString] via semantics
 * ([SemanticsProperties.Text]) rather than checking for two separate nodes:
 * the fix keeps stable+unstable flowing as one continuously word-wrapped
 * paragraph (two separate composables would force a hard line break between
 * them), so the "styling split" the bead asks for shows up as two different
 * [androidx.compose.ui.text.SpanStyle] colors within a single
 * [androidx.compose.ui.text.AnnotatedString], not as two nodes.
 *
 * [GraphicsMode.Mode.NATIVE] matches [com.montauk.voicecapture.screenshot.KeyScreensScreenshotTest]
 * / [LiveTranscriptPaneOverlongPartialTest] -- not strictly required for this
 * particular assertion (it inspects semantics/span metadata, not rendered
 * pixels or wrapped-line geometry), but kept for consistency with the other
 * Compose tests in this package.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveTranscriptPaneWordFinalityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        RecordingStateHolder.update { RecordingUiState() }
        TranscriptStateHolder.reset()
        // Bead vn-edu.66: the pane now gates on the effective AssemblyAI key
        // -- this test's fixture implies a real CONNECTED session (it seeds
        // currentPartial directly), so it needs a configured key or it would
        // hit the new keyless message instead of the pane under test here.
        val app: VoiceCaptureApp = ApplicationProvider.getApplicationContext()
        app.secretsStore.userAssemblyAiKey = "assemblyai-configured-test-key"
    }

    @Test
    fun stabilizedWordsPaintSolidWhileTheUnstableTailStaysDimmed() {
        val stableText = "so the marathon training"
        val unstableTail = "is going well but"
        TranscriptStateHolder.update {
            it.copy(
                connectionState = SttConnectionState.CONNECTED,
                currentPartial = "$stableText $unstableTail",
                partialStableText = stableText,
                partialUnstableTail = unstableTail,
                sourceLabel = "FILE",
            )
        }

        composeTestRule.setContent {
            RecordingScreen(onStopRecording = {})
        }
        composeTestRule.waitForIdle()

        val paneNode = composeTestRule.onNodeWithTag(LIVE_TRANSCRIPT_PANE_TEST_TAG).fetchSemanticsNode()
        val textNode = paneNode.children.single()
        val textKey = SemanticsProperties.Text
        val annotated = textNode.config[textKey].single()

        assertEquals("$stableText $unstableTail", annotated.text)

        // A point squarely inside the stable prefix, and a point squarely
        // inside the unstable tail -- not the exact boundary/first/last
        // character, so this isn't sensitive to how the space between the
        // two halves gets attributed to one span or the other.
        val stableProbeIndex = stableText.length / 2
        val unstableProbeIndex = annotated.text.length - (unstableTail.length / 2)

        val stableSpanColor = annotated.spanStyles
            .firstOrNull { stableProbeIndex in it.start until it.end }
            ?.item?.color
        val unstableSpanColor = annotated.spanStyles
            .firstOrNull { unstableProbeIndex in it.start until it.end }
            ?.item?.color

        assertNotNull("expected a color style covering the stable prefix, spans=${annotated.spanStyles}", stableSpanColor)
        assertNotNull("expected a color style covering the unstable tail, spans=${annotated.spanStyles}", unstableSpanColor)
        assertNotEquals(
            "expected the stable prefix and unstable tail to be painted with different colors/alphas " +
                "(solid vs dimmed) instead of the whole open turn sharing one uniform dimmed color -- " +
                "stable=$stableSpanColor unstable=$unstableSpanColor",
            stableSpanColor,
            unstableSpanColor,
        )
    }
}
