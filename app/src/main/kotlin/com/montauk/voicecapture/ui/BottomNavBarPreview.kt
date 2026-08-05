package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.session.SessionSummary
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import java.util.Date

/**
 * Fixture sessions for previews only -- not real [SessionSummary] data, just enough
 * variety (upload states, title lengths) to make the list read as a real screen rather
 * than a placeholder.
 */
private val PREVIEW_SESSIONS = listOf(
    SessionSummary(
        sessionId = "2026-08-05_0912_a9k2",
        title = "Standup notes",
        startedAt = Date(),
        durationMs = 7 * 60_000L + 3_000L,
        uploadState = UploadState.UPLOADED,
    ),
    SessionSummary(
        sessionId = "2026-08-04_1734_f0p1",
        title = "Design review with Priya",
        startedAt = Date(),
        durationMs = 22 * 60_000L + 41_000L,
        uploadState = UploadState.QUEUED,
    ),
    SessionSummary(
        sessionId = "2026-08-04_0851_c3d7",
        title = "Voice memo",
        startedAt = Date(),
        durationMs = 58_000L,
        uploadState = UploadState.LOCAL,
    ),
)

/**
 * Renders the full Sessions [Scaffold] -- bottom nav + list -- against fixture data, with
 * no [android.content.Context]/app-singleton dependency (unlike [SessionListScreen] itself,
 * which reads [com.montauk.voicecapture.VoiceCaptureApp] directly and so can't be previewed
 * standalone). This is the regression surface for vn-edu.33: the bug was a [BottomNavBar]
 * layout defect that only showed up once it sat inside a real [Scaffold] `bottomBar` slot
 * (see [NavigationBarItemHeight]'s doc comment in BottomNavBar.kt for the confirmed
 * mechanism) -- previewing [BottomNavBar] on its own would not have caught it, and did not.
 * Compare this preview before/after that fix to see the difference directly: the nav bar
 * must sit flush at the bottom at standard height, and the list above it must fill the
 * remaining space.
 */
@Preview(showBackground = true, heightDp = 800)
@Composable
private fun SessionsScaffoldPreview() {
    VoiceCaptureTheme {
        Scaffold(
            bottomBar = {
                BottomNavBar(
                    currentRoute = Routes.SESSIONS,
                    isConnectedToGithub = true,
                    onNewSession = {},
                    onNewSessionLongPress = {},
                    onSessions = {},
                    onSettings = {},
                    onAuthTapped = {},
                )
            },
        ) { contentPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(contentPadding), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text(text = "Sessions", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PREVIEW_SESSIONS, key = { it.sessionId }) { session ->
                            SessionRow(session = session, onClick = {})
                        }
                    }
                }
            }
        }
    }
}
