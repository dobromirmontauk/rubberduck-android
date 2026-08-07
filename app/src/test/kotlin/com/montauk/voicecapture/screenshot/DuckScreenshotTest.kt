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
import com.montauk.voicecapture.duck.DuckVisual
import com.montauk.voicecapture.duck.TagWordStatus
import com.montauk.voicecapture.duck.ThoughtCloud
import com.montauk.voicecapture.duck.ThoughtCloudWord
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
 * first frame of each state, including the real [ThoughtCloud] layout --
 * with `reducedMotion = true` so the cloud's own continuous drift/shimmer
 * doesn't introduce the same nondeterminism.
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
    fun duckAttentiveWithThoughtCloud() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ThoughtCloud(
                        words = listOf(
                            ThoughtCloudWord("family-trust", 0.9, TagWordStatus.EXISTING),
                            ThoughtCloudWord("kitchen-remodel", 0.85, TagWordStatus.PROPOSED),
                            ThoughtCloudWord("dog-walks", 0.8, TagWordStatus.APPROVED),
                            ThoughtCloudWord("contractors", 0.4, TagWordStatus.CANDIDATE),
                            ThoughtCloudWord("permits", 0.3, TagWordStatus.CANDIDATE),
                        ),
                        reducedMotion = true,
                        onApprove = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                    DuckPoseFrame(
                        visual = DuckVisual.Pose(DuckFrame.ATTENTIVE),
                        modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "duck_attentive_thought_cloud.png")
    }

    @Test
    fun duckSleep() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DuckPoseFrame(
                        visual = DuckVisual.Pose(DuckFrame.SLEEP),
                        modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "duck_sleep.png")
    }

    @Test
    fun duckThink() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DuckPoseFrame(
                        visual = DuckVisual.Pose(DuckFrame.THINK),
                        modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "duck_think.png")
    }

    @Test
    fun duckCelebrate() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DuckPoseFrame(
                        visual = DuckVisual.Pose(DuckFrame.CELEBRATE),
                        modifier = Modifier.fillMaxWidth(0.55f).aspectRatio(1f),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage(GOLDEN_DIR + "duck_celebrate.png")
    }
}
