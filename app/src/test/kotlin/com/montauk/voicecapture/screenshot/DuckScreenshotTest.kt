package com.montauk.voicecapture.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.montauk.voicecapture.duck.DuckFrame
import com.montauk.voicecapture.duck.DuckPoseFrame
import com.montauk.voicecapture.duck.DuckState
import com.montauk.voicecapture.duck.DuckVisual
import com.montauk.voicecapture.duck.TopicWordCloudTopics
import com.montauk.voicecapture.duck.WordCloud
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the duck view's self-contained pieces (bead asn-3sm),
 * built and checked in *before* any RecordingScreen integration -- see the
 * bead's "build order" note. Deliberately renders a single deterministic
 * frame via the internal [DuckPoseFrame] rather than the live, continuously
 * ticking [com.montauk.voicecapture.duck.DuckAnimator]: that composable's
 * frame loop is paced off-clock on purpose (see its KDoc) so a live
 * recording screen never stops animating, which also means capturing "the"
 * frame of a running instance would be nondeterministic across CI runs.
 * These goldens instead exercise exactly what a viewer would see at the
 * first frame of each state, including the real [WordCloud] layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class DuckScreenshotTest {

    companion object {
        private const val GOLDEN_DIR = "src/test/screenshot/goldens/"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun duckListeningWithWordCloud() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WordCloud(
                        topics = TopicWordCloudTopics(
                            confirmed = listOf("kitchen remodel", "budget", "timeline"),
                            candidates = listOf("vendor", "electrician"),
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                    DuckPoseFrame(
                        visual = DuckVisual.Pose(DuckFrame.IDLE_BREATHING_1),
                        state = DuckState.LISTENING,
                        modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "duck_listening_word_cloud.png")
    }

    @Test
    fun duckSleepy() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DuckPoseFrame(
                        visual = DuckVisual.Pose(DuckFrame.SLEEPY_1),
                        state = DuckState.SLEEPY,
                        modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "duck_sleepy.png")
    }

    @Test
    fun brbPaused() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DuckPoseFrame(
                        visual = DuckVisual.Brb,
                        state = DuckState.GONE_BRB,
                        modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "duck_brb_paused.png")
    }
}
