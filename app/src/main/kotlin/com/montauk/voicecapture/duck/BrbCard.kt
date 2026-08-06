package com.montauk.voicecapture.duck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * Hand-drawn-style "BRB" sign card standing in for the duck while recording
 * is paused, either kind (bead asn-3sm) -- see [DuckAnimator]'s GONE_BRB
 * branch. A slight tilt and warm paper color are the only "hand-drawn" cues;
 * deliberately simple rather than an actual illustration asset.
 */
@Composable
fun BrbCard(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(BRB_CARD_TEST_TAG), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .rotate(BRB_CARD_TILT_DEGREES)
                .background(BRB_CARD_PAPER_COLOR, RoundedCornerShape(6.dp))
                .border(BRB_CARD_BORDER_WIDTH, BRB_CARD_INK_COLOR, RoundedCornerShape(6.dp))
                .padding(horizontal = 28.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "brb!",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                color = BRB_CARD_INK_COLOR,
            )
        }
    }
}

const val BRB_CARD_TEST_TAG = "duck_brb_card"
private const val BRB_CARD_TILT_DEGREES = -4f
private val BRB_CARD_BORDER_WIDTH = 2.dp
private val BRB_CARD_PAPER_COLOR = Color(0xFFF4E9D8)
private val BRB_CARD_INK_COLOR = Color(0xFF3A2E22)

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun BrbCardPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp)) {
            BrbCard()
        }
    }
}
