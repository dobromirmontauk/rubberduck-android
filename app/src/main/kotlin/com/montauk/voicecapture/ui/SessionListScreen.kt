package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.session.SessionSummary
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.delay

private const val POST_STOP_POLL_ATTEMPTS = 8
private const val POST_STOP_POLL_INTERVAL_MS = 750L

@Composable
fun SessionListScreen(onSessionClick: (String) -> Unit) {
    val context = LocalContext.current
    val recordingState by RecordingStateHolder.state.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }

    // Re-reads on every fresh mount of this screen (including on navigation
    // back to it, since NavHost recomposes it fresh each time it re-enters
    // the back stack -- that's what picks up an upload-state change made
    // from the session-detail screen's Re-upload action). Also polls briefly
    // afterward: tapping Stop navigates here immediately, but the just-ended
    // session's meta.json isn't written until RecordingService's async
    // finalize (WAL -> ogg remux, see docs/audio-wal.md) completes, which for
    // a longer recording can take a few real seconds -- without this poll,
    // the session wouldn't appear until the user manually left this screen
    // and came back.
    LaunchedEffect(Unit) {
        val app = context.applicationContext as VoiceCaptureApp
        sessions = app.sessionStore.listSessions()
        repeat(POST_STOP_POLL_ATTEMPTS) {
            delay(POST_STOP_POLL_INTERVAL_MS)
            val refreshed = app.sessionStore.listSessions()
            if (refreshed != sessions) sessions = refreshed
        }
    }

    // Also re-reads immediately on any isRecording transition seen while this
    // screen stays mounted (e.g. a recording that finishes well after the
    // poll above has given up).
    LaunchedEffect(recordingState.isRecording) {
        val app = context.applicationContext as VoiceCaptureApp
        sessions = app.sessionStore.listSessions()
    }

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Bead vn-edu.29's quiet unconnected-state affordance: a fact, not a
                // prompt -- no button, no icon, no color pulled from the error/warning
                // end of the palette. "Connect GitHub" lives in Settings and the bottom
                // nav's "Sign In" tab; this just states where things stand.
                if (!(context.applicationContext as VoiceCaptureApp).secretsStore.isConnectedToGithub()) {
                    Text(
                        text = "Not connected to GitHub — sessions stay on this device",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (sessions.isEmpty()) {
                    Text(
                        text = "No sessions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessions, key = { it.sessionId }) { session ->
                            SessionRow(session = session, onClick = { onSessionClick(session.sessionId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatSessionMetaLine(session),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            UploadStateChip(session.uploadState)
        }
    }
}
