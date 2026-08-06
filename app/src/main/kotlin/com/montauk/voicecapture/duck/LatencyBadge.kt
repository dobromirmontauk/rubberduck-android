package com.montauk.voicecapture.duck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.montauk.voicecapture.service.LatencyBadgeUiState
import com.montauk.voicecapture.service.LatencySeverity

/**
 * The duck view's small latency warning badge (bead asn-3sm hooks only,
 * design-board section 3 "L3 + badge"): hidden entirely at
 * [LatencySeverity.OK], amber at [LatencySeverity.WARN], red at
 * [LatencySeverity.CRITICAL] -- "whimsy must not mask severity." [onTap] is
 * a stub for now (asn-55q's L2 HUD opens here once that bead lands); this
 * bead only reserves the slot and the tap affordance.
 */
@Composable
fun LatencyBadge(state: LatencyBadgeUiState, onTap: () -> Unit, modifier: Modifier = Modifier) {
    if (state.severity == LatencySeverity.OK) return
    val color = when (state.severity) {
        LatencySeverity.WARN -> LATENCY_WARN_COLOR
        LatencySeverity.CRITICAL -> LATENCY_CRITICAL_COLOR
        LatencySeverity.OK -> return
    }
    Box(
        modifier = modifier
            .testTag(LATENCY_BADGE_TEST_TAG)
            .background(LATENCY_BADGE_BACKGROUND, RoundedCornerShape(8.dp))
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = "⚠ ${state.message ?: "latency"}",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

const val LATENCY_BADGE_TEST_TAG = "duck_latency_badge"
private val LATENCY_BADGE_BACKGROUND = Color(0xFF2B241B)
private val LATENCY_WARN_COLOR = Color(0xFFE5A33C)
private val LATENCY_CRITICAL_COLOR = Color(0xFFE15B5B)
