package com.montauk.voicecapture.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.session.BulkArchiveEligibility
import com.montauk.voicecapture.session.DeleteConfirmPolicy
import com.montauk.voicecapture.session.PendingRemoval
import com.montauk.voicecapture.session.PendingRemovalHolder
import com.montauk.voicecapture.session.RemovalAction
import com.montauk.voicecapture.session.SessionStatus
import com.montauk.voicecapture.session.SessionStatusResolver
import com.montauk.voicecapture.session.SessionSummary
import com.montauk.voicecapture.session.SwipeHintStateHolder
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import com.montauk.voicecapture.vault.VaultSessionSnapshot
import kotlinx.coroutines.delay

private const val POST_STOP_POLL_ATTEMPTS = 8
private const val POST_STOP_POLL_INTERVAL_MS = 750L

@Composable
fun SessionListScreen(onSessionClick: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val recordingState by RecordingStateHolder.state.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var vaultSnapshot by remember { mutableStateOf(VaultSessionSnapshot.EMPTY) }
    // Bead vn-edu.55: long-press-to-delete (list row) and "Archive all
    // integrated sessions" (top action) state. Both actions only ever touch
    // sessionStore -- see SessionStore.deleteSession's KDoc for why neither
    // can reach the uploader/vault.
    var sessionPendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    var showBulkArchiveDialog by remember { mutableStateOf(false) }

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

    // Bead vn-edu.67: resyncs this screen's own list with disk whenever
    // PendingRemovalHolder's pending removal clears -- covers both outcomes
    // of a swiped delete/archive without this screen needing to know which
    // one happened. Undo: the file was never touched, so re-reading restores
    // the row exactly where sort order puts it (rather than this screen
    // tracking a separate "undo -> re-insert at index N" patch). Flush: the
    // file is already gone by the time this fires, so re-reading is a no-op
    // confirming what the earlier optimistic removeFromUiList() call already
    // reflected. Runs once harmlessly on initial mount too (pendingRemoval
    // starts null), overlapping with the LaunchedEffect(Unit) read above.
    val pendingRemoval by PendingRemovalHolder.state.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRemoval) {
        if (pendingRemoval == null) {
            sessions = app.sessionStore.listSessions()
        }
    }

    // Bead vn-edu.54: one vault-listing refresh per fresh mount of this
    // screen (same "re-enters the back stack" trigger as the sessions read
    // above) -- there's no pull-to-refresh affordance on this screen yet, so
    // "screen entry" is the only refresh trigger today. Keyless/no-vault
    // resolves to VaultSessionSnapshot.EMPTY inside the reader itself, so no
    // separate connected-to-GitHub check is needed here.
    LaunchedEffect(Unit) {
        val app = context.applicationContext as VoiceCaptureApp
        vaultSnapshot = app.currentVaultSessions()
    }

    // Bead vn-edu.55: eligibility for the bulk archive action -- only
    // sessions the vault has actually organized (INTEGRATED, not merely
    // UPLOADED), and only while vaultSnapshot is a live, current read.
    // `vaultSnapshot.stale` means this came from a keyless/offline cache
    // instead of a fresh fetch, so the action is disabled rather than acting
    // on possibly-outdated integration status (see the caption already
    // shown for the stale case below, which doubles as this action's
    // explanatory subtitle).
    val eligibleForBulkArchive = remember(sessions, vaultSnapshot) {
        BulkArchiveEligibility.eligibleSessionIds(sessions, vaultSnapshot.integratedSessionIds)
    }
    val bulkArchiveEnabled = eligibleForBulkArchive.isNotEmpty() && !vaultSnapshot.stale

    fun deleteSessionAndRefresh(sessionId: String) {
        app.sessionStore.deleteSession(sessionId)
        sessions = app.sessionStore.listSessions()
    }

    // Bead vn-edu.67: optimistic row removal for an instant swipe (delete of
    // an uploaded/integrated session, or archive of an integrated one) --
    // the on-disk removal itself is deferred to PendingRemovalHolder.flush,
    // not called here. Filters rather than re-reading sessionStore.listSessions()
    // since the file is deliberately still on disk at this point.
    fun removeFromUiList(sessionId: String) {
        sessions = sessions.filterNot { it.sessionId == sessionId }
    }

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Sessions",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showBulkArchiveDialog = true }, enabled = bulkArchiveEnabled) {
                        Icon(
                            imageVector = Icons.Filled.Archive,
                            contentDescription = "Archive all integrated sessions",
                            tint = if (bulkArchiveEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
                // Bead vn-edu.54: only shown when a live refresh attempt was made
                // and failed (offline/network error) and this snapshot came from
                // the on-disk cache instead -- keyless/no-vault never sets `stale`
                // (see VaultSessionReader.refresh), so no caption there either.
                val staleAsOfMs = vaultSnapshot.asOfMs
                if (vaultSnapshot.stale && staleAsOfMs != null) {
                    Text(
                        text = "Integration status as of ${formatAsOfTime(staleAsOfMs)}",
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
                            SessionRow(
                                session = session,
                                integratedSessionIds = vaultSnapshot.integratedSessionIds,
                                onClick = { onSessionClick(session.sessionId) },
                                onLongPress = { sessionPendingDelete = session },
                                // Bead vn-edu.67: swipe LEFT = delete, swipe RIGHT =
                                // archive. Both instant paths remove the row from
                                // this screen's own list immediately and defer the
                                // actual disk removal to PendingRemovalHolder --
                                // AppNavHost's Undo snackbar flushes or undoes it.
                                onSwipeDeleteInstant = { s ->
                                    removeFromUiList(s.sessionId)
                                    PendingRemovalHolder.schedule(
                                        PendingRemoval(s.sessionId, RemovalAction.DELETE, "Deleted"),
                                        execute = { app.sessionStore.deleteSession(s.sessionId) },
                                    )
                                },
                                // Swipe-left on a LOCAL/QUEUED (never-uploaded) session
                                // settles back instead of committing (see SessionRow's
                                // confirmValueChange) -- this just opens the same hard-
                                // confirm dialog long-press already uses, no different
                                // treatment for a swipe than a long-press here.
                                onSwipeDeleteRequiresConfirm = { s -> sessionPendingDelete = s },
                                onSwipeArchiveInstant = { s ->
                                    removeFromUiList(s.sessionId)
                                    PendingRemovalHolder.schedule(
                                        PendingRemoval(s.sessionId, RemovalAction.ARCHIVE, "Archived"),
                                        execute = { app.sessionStore.deleteSession(s.sessionId) },
                                    )
                                },
                                onSwipeArchiveIneligible = { SwipeHintStateHolder.show("Not yet integrated") },
                            )
                        }
                    }
                }
            }
        }
    }

    sessionPendingDelete?.let { pending ->
        DeleteSessionConfirmDialog(
            uploadState = pending.uploadState,
            onConfirm = {
                deleteSessionAndRefresh(pending.sessionId)
                sessionPendingDelete = null
            },
            onDismiss = { sessionPendingDelete = null },
        )
    }

    if (showBulkArchiveDialog) {
        BulkArchiveConfirmDialog(
            count = eligibleForBulkArchive.size,
            onConfirm = {
                eligibleForBulkArchive.forEach { sessionId -> app.sessionStore.deleteSession(sessionId) }
                sessions = app.sessionStore.listSessions()
                showBulkArchiveDialog = false
            },
            onDismiss = { showBulkArchiveDialog = false },
        )
    }
}

// Visibility widened from `private` (not `private` -> business-logic reasons; this is a
// pure, stateless row with no Context/app dependency) so the vn-edu.33 Sessions-scaffold
// preview in BottomNavBarPreview.kt can render real-looking rows from fixture data.
//
// Bead vn-edu.55: [onLongPress] opens the per-session "delete from phone"
// confirm -- deliberately a long-press on the existing row rather than a new
// visible overflow icon, so this row's *static* appearance (and therefore
// sessions_with_nav.png's committed pixels) doesn't change. Switched from
// Card's own `onClick` overload to a plain Card + Modifier.combinedClickable
// since Material3's clickable-Card overload has no long-press slot.
//
// Bead vn-edu.67: the Card is now [SwipeToDismissBox]'s `content` slot, at
// rest fully covering `backgroundContent` -- a swipe reveals the red/green
// background underneath, but with zero horizontal offset (no gesture in
// progress) this row's own pixels are unchanged from before this bead, which
// is what keeps sessions_with_nav.png's committed golden valid. All four new
// `onSwipe*` params default to no-ops so BottomNavBarPreview.kt's existing
// `SessionRow(session = session, onClick = {})` call keeps compiling as-is.
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SessionRow(
    session: SessionSummary,
    integratedSessionIds: Set<String> = emptySet(),
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onSwipeDeleteInstant: (SessionSummary) -> Unit = {},
    onSwipeDeleteRequiresConfirm: (SessionSummary) -> Unit = {},
    onSwipeArchiveInstant: (SessionSummary) -> Unit = {},
    onSwipeArchiveIneligible: () -> Unit = {},
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                // Swipe LEFT (Material3's EndToStart, i.e. "from the end edge
                // toward the start edge" in this LTR layout): delete. Only an
                // already-uploaded session's swipe commits outright -- a
                // never-uploaded (LOCAL/QUEUED) one settles back (false) and
                // opens vn-edu.55's hard-confirm dialog instead, exactly like a
                // long-press; no instant destruction of unuploaded audio.
                SwipeToDismissBoxValue.EndToStart -> {
                    if (DeleteConfirmPolicy.requiresHardWarning(session.uploadState)) {
                        onSwipeDeleteRequiresConfirm(session)
                        false
                    } else {
                        onSwipeDeleteInstant(session)
                        true
                    }
                }
                // Swipe RIGHT (StartToEnd): archive, gated to INTEGRATED (same
                // eligibility BulkArchiveEligibility uses for the bulk action).
                // Anything else settles back with a hint -- no different from
                // an accidental swipe.
                SwipeToDismissBoxValue.StartToEnd -> {
                    val status = SessionStatusResolver.resolve(session.uploadState, session.sessionId, integratedSessionIds)
                    if (status == SessionStatus.INTEGRATED) {
                        onSwipeArchiveInstant(session)
                        true
                    } else {
                        onSwipeArchiveIneligible()
                        false
                    }
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        // Test hook only (bead vn-edu.67) -- a testTag is a zero-size semantics
        // property, not a rendered element, so it doesn't touch this row's
        // pixels or sessions_with_nav.png. Robolectric's performTouchInput needs
        // a node spanning the *whole* row to swipe far enough to cross
        // SwipeToDismissBox's positional threshold; onNodeWithText's bounds
        // (just the title label) are too narrow for that.
        modifier = Modifier.testTag("session-swipe-${session.sessionId}"),
        backgroundContent = { SwipeRowBackground(dismissState) },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
                SessionStatusChip(SessionStatusResolver.resolve(session.uploadState, session.sessionId, integratedSessionIds))
            }
        }
    }
}

/** Not part of the Material3 color scheme (no "success"/archive role defined in Theme.kt) -- a plain, theme-independent green reads fine against either the red delete counterpart or a white icon. */
private val SwipeArchiveGreen = Color(0xFF2E7D32)

/** [SwipeToDismissBox]'s `backgroundContent` -- red+delete-icon revealed from the right on a left swipe, green+archive-icon revealed from the left on a right swipe. Fully hidden behind the row at rest (dismissDirection == Settled), which is what keeps the static golden unaffected -- see [SessionRow]'s KDoc. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRowBackground(dismissState: SwipeToDismissBoxState) {
    val direction = dismissState.dismissDirection
    val color = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeArchiveGreen
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
        SwipeToDismissBoxValue.Settled -> Color.Transparent
    }
    Box(
        modifier = Modifier.fillMaxSize().background(color, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> Icon(imageVector = Icons.Filled.Archive, contentDescription = "Archive", tint = Color.White)
            SwipeToDismissBoxValue.EndToStart -> Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
            SwipeToDismissBoxValue.Settled -> {}
        }
    }
}
