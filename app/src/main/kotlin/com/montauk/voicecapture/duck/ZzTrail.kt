package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The rising "z Z Z" trail (design board v2, quiet-and-pause storyboard
 * frames 8-10): three Z's of increasing size climbing diagonally from the
 * duck's head, each on its own float-up loop, shown during [DuckState.DROWSY]
 * (bead asn-3h6: sustained quiet, still recording -- "gets heavy-lidded with
 * a rising trail of Z's") and [DuckState.SLEEP] (either pause kind -- a UI
 * overlay, not baked into the pose pixels). Static (no float, full opacity)
 * when [reducedMotion].
 */
@Composable
fun ZzTrail(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(ZZ_TRAIL_TEST_TAG)) {
        Zzz(
            text = "z", fontSizeSp = 18, baseAlpha = 0.8f, periodMs = 2_500, reducedMotion = reducedMotion,
            modifier = Modifier.offset(x = 34.dp, y = 8.dp),
        )
        Zzz(
            text = "Z", fontSizeSp = 27, baseAlpha = 0.9f, periodMs = 2_650, reducedMotion = reducedMotion,
            modifier = Modifier.offset(x = 22.dp, y = (-16).dp),
        )
        Zzz(
            text = "Z", fontSizeSp = 38, baseAlpha = 1f, periodMs = 2_800, reducedMotion = reducedMotion,
            modifier = Modifier.offset(x = 8.dp, y = (-46).dp),
        )
    }
}

@Composable
private fun Zzz(
    text: String,
    fontSizeSp: Int,
    baseAlpha: Float,
    periodMs: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val riseT = if (reducedMotion) 0f else rememberLoopingPhase(periodMs = periodMs, key = "$text-$fontSizeSp")
    // Rises and fades out as it climbs -- a "steam" float, not a hard cut
    // back to the start when the loop restarts.
    val alpha = if (reducedMotion) baseAlpha else baseAlpha * (1f - riseT).coerceIn(0f, 1f)
    Text(
        text = text,
        fontSize = fontSizeSp.sp,
        fontWeight = FontWeight.Bold,
        color = ZZ_COLOR,
        modifier = modifier
            .graphicsLayer { translationY = -riseT * (fontSizeSp * ZZ_RISE_MULTIPLIER) }
            .alpha(alpha),
    )
}

const val ZZ_TRAIL_TEST_TAG = "duck_zz_trail"
private val ZZ_COLOR = Color(0xFFC99E1E) // duck-deep, matches design board --duck-deep
private const val ZZ_RISE_MULTIPLIER = 2.2f
