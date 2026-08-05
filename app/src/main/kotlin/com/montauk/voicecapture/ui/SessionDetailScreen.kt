package com.montauk.voicecapture.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.session.LiveTranscriptLine
import com.montauk.voicecapture.session.SessionMeta
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import com.montauk.voicecapture.upload.UploadWorker
import java.io.File

private enum class DetailTab(val label: String) {
    LIVE_TEXT("Live text"),
    CANONICAL("Canonical"),
    FILED_TO("Filed to"),
}

/**
 * Session detail: header, audio playback, a Live text / Canonical / Filed to
 * tab set, and Re-upload / Share audio actions. Canonical and Filed to are
 * stubbed -- they only populate after a vault-side organize run, which is
 * out of scope for this wave.
 */
@Composable
fun SessionDetailScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp

    var meta by remember { mutableStateOf<SessionMeta?>(null) }
    var transcriptLines by remember { mutableStateOf<List<LiveTranscriptLine>>(emptyList()) }
    var uploadState by remember { mutableStateOf(UploadState.LOCAL) }
    var selectedTab by remember { mutableStateOf(DetailTab.LIVE_TEXT) }

    LaunchedEffect(sessionId) {
        meta = app.sessionStore.readMeta(sessionId)
        transcriptLines = app.sessionStore.readTranscriptLines(sessionId)
        uploadState = app.sessionStore.readUploadState(app.sessionStore.sessionDir(sessionId))
    }

    val oggFile = remember(sessionId) { app.sessionStore.oggFile(app.sessionStore.sessionDir(sessionId)) }

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Spacer(modifier = Modifier.height(12.dp))
                DetailHeader(sessionId = sessionId, uploadState = uploadState, onBack = onBack)
                Spacer(modifier = Modifier.height(16.dp))
                AudioPlaybackRow(oggFile = oggFile)
                Spacer(modifier = Modifier.height(20.dp))
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    DetailTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        DetailTab.LIVE_TEXT -> LiveTextTab(transcriptLines)
                        DetailTab.CANONICAL -> StubTab("Appears after an organize run — coming soon")
                        DetailTab.FILED_TO -> StubTab("Appears after an organize run — coming soon")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                ActionsRow(
                    onReupload = {
                        app.sessionStore.setUploadState(app.sessionStore.sessionDir(sessionId), UploadState.QUEUED)
                        uploadState = UploadState.QUEUED
                        UploadWorker.enqueue(context, sessionId)
                    },
                    onShare = { shareAudio(context, oggFile) },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DetailHeader(sessionId: String, uploadState: UploadState, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = sessionId,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        UploadStateChip(uploadState)
    }
}

@Composable
private fun AudioPlaybackRow(oggFile: File) {
    if (!oggFile.exists()) {
        Text(
            text = "Audio not available yet",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val player = rememberAudioPlayer(oggFile.absolutePath)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = { player.togglePlayPause() }, enabled = player.isAvailable) {
            Icon(
                imageVector = if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (player.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val progress = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatMillis(player.positionMs)} / ${formatMillis(player.durationMs)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!player.isAvailable) {
        Text(
            text = "Playback isn't available for this file",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveTextTab(lines: List<LiveTranscriptLine>) {
    if (lines.isEmpty()) {
        Text(
            text = "No live transcript — recorded offline or transcription was off.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(lines) { line ->
            Column {
                Text(
                    text = formatTranscriptTimestamp(line.t0Ms),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun StubTab(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActionsRow(onReupload: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onReupload, modifier = Modifier.weight(1f)) {
            Text("Re-upload")
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.height(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Share audio")
        }
    }
}

private fun shareAudio(context: android.content.Context, oggFile: File) {
    if (!oggFile.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", oggFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/ogg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share session audio"))
}
