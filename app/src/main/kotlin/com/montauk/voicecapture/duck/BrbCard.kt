package com.montauk.voicecapture.duck

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.R
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * The claymation "BRB" sign prop (bead asn-ek1's `46-brb-sign-01.png`,
 * closing the design board's asset-gap note) standing in for the duck while
 * recording is paused, either kind (bead asn-3sm) -- see [DuckAnimator]'s
 * GONE_BRB branch and the design board's "duck walks off (waddle), sign
 * remains" framing. The duck itself doesn't walk off on-screen (no waddle
 * transition is built this round -- see this bead's landing report); the
 * card crossfades in directly. A slight tilt is the only added touch, to
 * read as a sign someone actually planted rather than a flat overlay.
 */
@Composable
fun BrbCard(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(BRB_CARD_TEST_TAG), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.duck_brb_sign),
            contentDescription = "Back in a moment",
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .rotate(BRB_SIGN_TILT_DEGREES),
        )
    }
}

const val BRB_CARD_TEST_TAG = "duck_brb_card"
private const val BRB_SIGN_TILT_DEGREES = -4f

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun BrbCardPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            BrbCard()
        }
    }
}
