package com.montauk.voicecapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.stt.SttConnectionState

/** Small pill-shaped status label, shared by the recording, list, and detail screens. */
@Composable
fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UploadStateChip(state: UploadState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        UploadState.LOCAL -> "LOCAL" to MaterialTheme.colorScheme.onSurfaceVariant
        UploadState.QUEUED -> "QUEUED" to Color(0xFFE8A33D)
        UploadState.UPLOADED -> "UPLOADED" to Color(0xFF5FBF6E)
    }
    StatusChip(label, color, modifier)
}

/** LIVE/OFFLINE (+ transient CONNECTING/RECONNECTING) chip for the recording screen. */
@Composable
fun SttStatusChip(state: SttConnectionState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        SttConnectionState.DISABLED -> "OFFLINE" to MaterialTheme.colorScheme.onSurfaceVariant
        SttConnectionState.CONNECTING -> "CONNECTING" to Color(0xFFE8A33D)
        SttConnectionState.CONNECTED -> "LIVE" to Color(0xFF5FBF6E)
        SttConnectionState.DROPPED -> "RECONNECTING" to Color(0xFFE8A33D)
    }
    StatusChip(label, color, modifier)
}
