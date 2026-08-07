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

    private fun setUpSleepingStage() {
        composeTestRule.setContent {
            VoiceCaptureTheme {
                Box(modifier = Modifier.width(360.dp).height(640.dp)) {
                    DuckStage(
                        duckState = DuckState.SLEEP,
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
        setUpSleepingStage()

        val duckBounds = composeTestRule.onNodeWithTag(DUCK_ANIMATOR_TEST_TAG).getUnclippedBoundsInRoot()
        val trailBounds = composeTestRule.onNodeWithTag(ZZ_TRAIL_TEST_TAG).getUnclippedBoundsInRoot()

        assertTrue("trail top ${trailBounds.top} should equal duck box top ${duckBounds.top}", trailBounds.top == duckBounds.top)
        assertTrue("trail bottom ${trailBounds.bottom} should equal duck box bottom ${duckBounds.bottom}", trailBounds.bottom == duckBounds.bottom)
    }

    @Test
    fun `the smallest z sits right at the duck box's top edge -- the head -- not floating far above it`() {
        setUpSleepingStage()

        val duckBounds = composeTestRule.onNodeWithTag(DUCK_ANIMATOR_TEST_TAG).getUnclippedBoundsInRoot()
        val smallestZBounds = composeTestRule.onNodeWithText("z").getUnclippedBoundsInRoot()

        // "Touching the head silhouette": within a small tolerance of the
        // duck box's own top edge (which DuckPoseFrame's TopCenter fix makes
        // the actual rendered head), not the old bug's ~46dp overshoot
        // clear above it (or the even older bug's ~600dp overshoot below,
        // near the duck's feet).
        val tolerance = 24.dp
        assertTrue(
            "smallest z top ${smallestZBounds.top} should be within $tolerance of the head at ${duckBounds.top}",
            smallestZBounds.top >= duckBounds.top - tolerance && smallestZBounds.top <= duckBounds.top + tolerance,
        )
    }

    @Test
    fun `the trail ascends up-and-right -- the biggest Z is higher and further right than the smallest z`() {
        setUpSleepingStage()

        val smallZBounds = composeTestRule.onNodeWithText("z").getUnclippedBoundsInRoot()
        // Two "Z" nodes exist (medium, then large, in composition order) --
        // index 1 is the larger, topmost one.
        val bigZBounds = composeTestRule.onAllNodesWithText("Z")[1].getUnclippedBoundsInRoot()

        assertTrue("biggest Z (${bigZBounds.top}) should be higher than smallest z (${smallZBounds.top})", bigZBounds.top < smallZBounds.top)
        assertTrue("biggest Z (${bigZBounds.left}) should be further right than smallest z (${smallZBounds.left})", bigZBounds.left > smallZBounds.left)
    }
}
