package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * frames 8-10): three Z's of increasing size climbing diagonally
 * up-and-right, shown during [DuckState.DROWSY] (bead asn-3h6: sustained
 * quiet, still recording -- "gets heavy-lidded with a rising trail of Z's")
 * and [DuckState.SLEEP] (either pause kind) -- a UI overlay, not baked into
 * the pose pixels, so it works over whatever pose either state resolves to.
 * Static (no float, full opacity) when [reducedMotion]. [DuckStage] alone
 * decides which states show it; this composable itself has no state-name
 * dependency at all, so a future third quiet/dozing tier wouldn't need a
 * change here either.
 *
 * **Origin at the head (bead asn-kd2).** [modifier] is expected to carry
 * [Alignment.TopCenter] content alignment from [DuckStage] over a box whose
 * top edge is the duck's actual head (see [DuckPoseFrame]'s KDoc for why
 * that box's top edge lines up with the head now) -- this composable's own
 * (0,0) is therefore the top-center of the head silhouette. The smallest
 * "z" sits right at that origin (barely inset, just touching the crown);
 * each larger Z then steps further right (`x` grows) and further up (`y`
 * grows more negative), so the whole trail reads as climbing away from the
 * head up and to the right, matching the storyboard reference exactly
 * (previously these three offsets ran the opposite diagonal -- up and to
 * the *left* -- while also being anchored 40dp above the box's true bottom
 * edge rather than the head at all; see git history for the pre-fix
 * version this replaced).
 */
@Composable
fun ZzTrail(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(ZZ_TRAIL_TEST_TAG), contentAlignment = Alignment.TopCenter) {
        Zzz(
            text = "z", fontSizeSp = 18, baseAlpha = 0.8f, periodMs = 2_500, reducedMotion = reducedMotion,
            modifier = Modifier.offset(x = 6.dp, y = 2.dp),
        )
        Zzz(
            text = "Z", fontSizeSp = 27, baseAlpha = 0.9f, periodMs = 2_650, reducedMotion = reducedMotion,
            modifier = Modifier.offset(x = 26.dp, y = (-24).dp),
        )
        Zzz(
            text = "Z", fontSizeSp = 38, baseAlpha = 1f, periodMs = 2_800, reducedMotion = reducedMotion,
            modifier = Modifier.offset(x = 48.dp, y = (-54).dp),
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
