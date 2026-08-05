package com.montauk.voicecapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.TranscriptLine
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.service.TranscriptUiState
import com.montauk.voicecapture.session.SessionSummary
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.stt.SttConnectionState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

@Composable
fun VoiceCaptureScreen(
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val context = LocalContext.current
    val recordingState by RecordingStateHolder.state.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }

    LaunchedEffect(recordingState.isRecording) {
        val app = context.applicationContext as VoiceCaptureApp
        sessions = app.sessionStore.listSessions()
    }

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                ElapsedTime(elapsedMs = recordingState.elapsedMs, isRecording = recordingState.isRecording)
                Spacer(modifier = Modifier.height(32.dp))
                RecordButton(
                    isRecording = recordingState.isRecording,
                    onClick = { if (recordingState.isRecording) onStopRecording() else onStartRecording() },
                )
                Spacer(modifier = Modifier.height(32.dp))
                LiveTranscriptPane(isRecording = recordingState.isRecording)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                SessionList(sessions = sessions)
            }
        }
    }
}

@Composable
private fun ElapsedTime(elapsedMs: Long, isRecording: Boolean) {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    Text(
        text = String.format("%02d:%02d", minutes, seconds),
        style = MaterialTheme.typography.displayLarge,
        color = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(120.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            text = if (isRecording) "Stop" else "Rec",
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

/**
 * Live streaming transcript: finalized lines solid and newest-at-bottom,
 * the current in-progress line (if any) dimmed underneath them. Backed by
 * [TranscriptStateHolder], which [com.montauk.voicecapture.service.RecordingService]
 * fills from the AssemblyAI [com.montauk.voicecapture.stt.StreamingSttClient].
 */
@Composable
private fun LiveTranscriptPane(isRecording: Boolean) {
    val transcript by TranscriptStateHolder.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth()) {
        if (isRecording) {
            SttStatusChip(transcript.connectionState)
            Spacer(modifier = Modifier.height(8.dp))
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 220.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
        ) {
            when {
                !isRecording && transcript.finalLines.isEmpty() -> EmptyTranscriptHint(
                    text = "Transcript will appear here while recording",
                )
                isRecording && transcript.connectionState == SttConnectionState.DISABLED -> EmptyTranscriptHint(
                    text = "Live transcription off -- recording continues normally",
                )
                else -> TranscriptLines(transcript)
            }
        }
    }
}

@Composable
private fun EmptyTranscriptHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterStart) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TranscriptLines(transcript: TranscriptUiState) {
    val listState = rememberLazyListState()
    val itemCount = transcript.finalLines.size + if (transcript.currentPartial.isNotBlank()) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(transcript.finalLines) { line: TranscriptLine ->
            Text(text = line.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (transcript.currentPartial.isNotBlank()) {
            item {
                Text(
                    text = transcript.currentPartial,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun SttStatusChip(state: SttConnectionState) {
    val (label, color) = when (state) {
        SttConnectionState.DISABLED -> "Live transcription off" to MaterialTheme.colorScheme.onSurfaceVariant
        SttConnectionState.CONNECTING -> "Connecting..." to Color(0xFFE8A33D)
        SttConnectionState.CONNECTED -> "Live" to Color(0xFF5FBF6E)
        SttConnectionState.DROPPED -> "Reconnecting..." to Color(0xFFE8A33D)
    }
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SessionList(sessions: List<SessionSummary>) {
    if (sessions.isEmpty()) {
        Text(
            text = "No sessions yet",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sessions) { session -> SessionRow(session) }
    }
}

@Composable
private fun SessionRow(session: SessionSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = session.sessionId, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(text = formatDuration(session.durationMs), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            UploadStateChip(session.uploadState)
        }
    }
}

@Composable
private fun UploadStateChip(state: UploadState) {
    val (label, color) = when (state) {
        UploadState.LOCAL -> "LOCAL" to MaterialTheme.colorScheme.onSurfaceVariant
        UploadState.QUEUED -> "QUEUED" to Color(0xFFE8A33D)
        UploadState.UPLOADED -> "UPLOADED" to Color(0xFF5FBF6E)
    }
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d min %02d sec", minutes, seconds)
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun VoiceCaptureScreenPreview() {
    VoiceCaptureTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(24.dp)) {
                ElapsedTime(elapsedMs = 754_000L, isRecording = true)
                Spacer(modifier = Modifier.height(16.dp))
                RecordButton(isRecording = true, onClick = {})
            }
        }
    }
}
