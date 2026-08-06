package com.montauk.voicecapture.duck

import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Two small dots rising from the duck's head toward the thought cloud (bead
 * asn-3sm, design-board section 2's `.thought-dots`) -- purely decorative,
 * ties the cloud visually back to the duck as "his" thoughts. Static
 * (full opacity, no motion) when [reducedMotion].
 */
@Composable
fun ThoughtBubbleDots(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(THOUGHT_BUBBLE_DOTS_TEST_TAG)) {
        Dot(sizeDp = 5.dp, reducedMotion = reducedMotion, periodMs = 2200, modifier = Modifier)
        Dot(sizeDp = 8.dp, reducedMotion = reducedMotion, periodMs = 2600, modifier = Modifier.size(8.dp))
    }
}

@Composable
private fun Dot(sizeDp: androidx.compose.ui.unit.Dp, reducedMotion: Boolean, periodMs: Int, modifier: Modifier = Modifier) {
    val alphaValue = if (reducedMotion) {
        DOT_BASE_ALPHA
    } else {
        val phase = rememberLoopingPhase(periodMs = periodMs, repeatMode = RepeatMode.Reverse, key = periodMs)
        DOT_BASE_ALPHA + (DOT_PEAK_ALPHA - DOT_BASE_ALPHA) * phase
    }
    Box(
        modifier = modifier
            .size(sizeDp)
            .alpha(alphaValue)
            .background(DOT_COLOR, CircleShape),
    )
}

const val THOUGHT_BUBBLE_DOTS_TEST_TAG = "duck_thought_bubble_dots"
private val DOT_COLOR = Color(0xFF3A332A)
private const val DOT_BASE_ALPHA = 0.5f
private const val DOT_PEAK_ALPHA = 0.85f
