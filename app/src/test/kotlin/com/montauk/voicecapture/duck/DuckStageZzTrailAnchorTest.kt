package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.service.LatencyBadgeUiState
import com.montauk.voicecapture.service.SummaryUiState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead asn-kd2: the Z-trail must originate AT the duck's head -- the
 * smallest "z" touching the head silhouette, the trail then climbing
 * up-and-right -- not floating in empty space above the duck (the pre-fix
 * bug: the trail assumed [DuckStage]'s `fillMaxHeight(DUCK_HEIGHT_FRACTION)`
 * box was fully occupied by the duck sprite edge-to-edge, when in fact
 * [DuckPoseFrame]'s square asset only filled part of that tall box, leaving
 * the head well below the box's own top edge -- see [DuckPoseFrame]'s KDoc
 * for the [androidx.compose.ui.Alignment.TopCenter] fix that closed that
 * gap, and [ZzTrail]'s KDoc for why its own origin now lines up with it).
 *
 * Rendered at a realistic tall phone aspect (360x640dp) -- the bug only
 * reproduced under a box taller than the square duck asset; a square host
 * box would have hidden it entirely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuckStageZzTrailAnchorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setUpDozingStage(duckState: DuckState) {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.width(360.dp).height(640.dp)) {
                    DuckStage(
                        duckState = duckState,
                        words = ThoughtCloudWords.EMPTY,
                        reducedMotion = true,
                        onApproveWord = {},
                        summary = SummaryUiState(),
                        latencyState = LatencyBadgeUiState(),
                        onLatencyBadgeTap = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the zz trail's box shares the exact bounds of the duck animator's box`() {
        setUpDozingStage(DuckState.SLEEP)

        val duckBounds = composeTestRule.onNodeWithTag(DUCK_ANIMATOR_TEST_TAG).getUnclippedBoundsInRoot()
        val trailBounds = composeTestRule.onNodeWithTag(ZZ_TRAIL_TEST_TAG).getUnclippedBoundsInRoot()

        assertTrue("trail top ${trailBounds.top} should equal duck box top ${duckBounds.top}", trailBounds.top == duckBounds.top)
        assertTrue("trail bottom ${trailBounds.bottom} should equal duck box bottom ${duckBounds.bottom}", trailBounds.bottom == duckBounds.bottom)
    }

    /**
     * Bead asn-kd2.4: "smallest z within ~16dp of the duck's head apex,"
     * in BOTH [DuckState.DROWSY] (bead asn-3h6: sustained quiet, still
     * recording) and [DuckState.SLEEP] (either pause kind) -- tightened
     * from an earlier ~24dp tolerance. Checks BOTH axes -- the smallest
     * z's top-left corner against the head apex point (the duck box's own
     * top-center, where [DuckPoseFrame]'s [androidx.compose.ui.Alignment.TopCenter]
     * fix places the actual rendered head) -- not just vertical proximity,
     * since "touching the silhouette" means close in both x and y.
     */
    @Test
    fun `the smallest z sits within 16dp of the duck's head apex in SLEEP`() {
        assertSmallestZWithinToleranceOfHeadApex(DuckState.SLEEP)
    }

    @Test
    fun `the smallest z sits within 16dp of the duck's head apex in DROWSY`() {
        assertSmallestZWithinToleranceOfHeadApex(DuckState.DROWSY)
    }

    private fun assertSmallestZWithinToleranceOfHeadApex(duckState: DuckState) {
        setUpDozingStage(duckState)

        val duckBounds = composeTestRule.onNodeWithTag(DUCK_ANIMATOR_TEST_TAG).getUnclippedBoundsInRoot()
        val smallestZBounds = composeTestRule.onNodeWithText("z").getUnclippedBoundsInRoot()
        val headApexX = (duckBounds.left + duckBounds.right) / 2
        val headApexY = duckBounds.top

        val tolerance = 16.dp
        val dx = (smallestZBounds.left - headApexX).value
        val dy = (smallestZBounds.top - headApexY).value
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue(
            "[$duckState] smallest z (left=${smallestZBounds.left}, top=${smallestZBounds.top}) should be within $tolerance of the head apex (x=$headApexX, y=$headApexY), was ${distance}dp",
            distance <= tolerance.value,
        )
    }

    @Test
    fun `the trail ascends up-and-right -- the biggest Z is higher and further right than the smallest z`() {
        setUpDozingStage(DuckState.SLEEP)

        val smallZBounds = composeTestRule.onNodeWithText("z").getUnclippedBoundsInRoot()
        // Two "Z" nodes exist (medium, then large, in composition order) --
        // index 1 is the larger, topmost one.
        val bigZBounds = composeTestRule.onAllNodesWithText("Z")[1].getUnclippedBoundsInRoot()

        assertTrue("biggest Z (${bigZBounds.top}) should be higher than smallest z (${smallZBounds.top})", bigZBounds.top < smallZBounds.top)
        assertTrue("biggest Z (${bigZBounds.left}) should be further right than smallest z (${smallZBounds.left})", bigZBounds.left > smallZBounds.left)
    }
}
