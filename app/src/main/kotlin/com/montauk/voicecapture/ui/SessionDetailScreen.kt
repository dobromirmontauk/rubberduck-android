package com.montauk.voicecapture.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.session.DerivedTitle
import com.montauk.voicecapture.session.DetailTabPlaceholder
import com.montauk.voicecapture.session.LiveTranscriptLine
import com.montauk.voicecapture.session.SessionMeta
import com.montauk.voicecapture.session.SessionStatus
import com.montauk.voicecapture.session.SessionStatusResolver
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import com.montauk.voicecapture.upload.UploadWorker
import com.montauk.voicecapture.vault.CanonicalTranscript
import com.montauk.voicecapture.vault.CanonicalTranscriptParser
import com.montauk.voicecapture.vault.FiledToRenderModel
import com.montauk.voicecapture.vault.OrganizationDocument
import com.montauk.voicecapture.vault.OrganizationDocumentParser
import com.montauk.voicecapture.vault.OrganizationFragment
import com.montauk.voicecapture.vault.OrganizationTotals
import com.montauk.voicecapture.vault.OrganizationUnassignedSpan
import java.io.File

private enum class DetailTab(val label: String) {
    LIVE_TEXT("Live text"),
    CANONICAL("Canonical"),
    FILED_TO("Filed to"),
}

/**
 * Session detail: header, audio playback, a Live text / Canonical / Filed to
 * tab set, and Re-upload / Share audio actions. Canonical (`transcript.json`)
 * and Filed to (`organization.json`/`organization.md`) are fed lazily from
 * the vault on first tab open (bead vn-edu.57) -- see [CanonicalTab] and
 * [FiledToTab] for the placeholder/loading/loaded state machine, keyed off
 * [SessionStatus] from [SessionStatusResolver].
 */
@Composable
fun SessionDetailScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp

    var meta by remember { mutableStateOf<SessionMeta?>(null) }
    var transcriptLines by remember { mutableStateOf<List<LiveTranscriptLine>>(emptyList()) }
    var uploadState by remember { mutableStateOf(UploadState.LOCAL) }
    var selectedTab by remember { mutableStateOf(DetailTab.LIVE_TEXT) }

    // Bead vn-edu.57: null until the first LaunchedEffect below resolves it --
    // distinct from any real SessionStatus so a tab opened before that
    // resolution lands (rare, but possible on a slow vault fetch) shows a
    // loading indicator rather than briefly flashing a wrong placeholder.
    var sessionStatus by remember { mutableStateOf<SessionStatus?>(null) }
    var canonicalTranscript by remember { mutableStateOf<CanonicalTranscript?>(null) }
    var canonicalLoadFailed by remember { mutableStateOf(false) }
    var filedToModel by remember { mutableStateOf<FiledToRenderModel?>(null) }

    LaunchedEffect(sessionId) {
        val loadedMeta = app.sessionStore.readMeta(sessionId)
        val loadedLines = app.sessionStore.readTranscriptLines(sessionId)
        meta = loadedMeta
        transcriptLines = loadedLines
        val loadedUploadState = app.sessionStore.readUploadState(app.sessionStore.sessionDir(sessionId))
        uploadState = loadedUploadState
        val vaultSnapshot = app.currentVaultSessions()
        sessionStatus = SessionStatusResolver.resolve(loadedUploadState, sessionId, vaultSnapshot.integratedSessionIds)

        // Derive-on-demand backfill (bead vn-edu.42): an older, already-
        // transcribed session can still have meta.title == null -- it
        // finished before this feature existed, or its post-finalize title
        // call failed/timed out/ran keyless at the time. Opening the detail
        // view is as good a trigger as any to try once: a no-op if there's
        // still no key configured (TitleGeneratorFactory.create returns
        // null) or the transcript is empty, and it never blocks the rest of
        // this screen from rendering with the derived title in the meantime.
        if (loadedMeta != null && loadedMeta.title.isNullOrBlank() && loadedLines.isNotEmpty()) {
            val generated = app.newTitleGenerator()?.generate(loadedLines.map { it.text })
            if (!generated.isNullOrBlank()) {
                app.sessionStore.updateTitle(sessionId, generated)
                meta = loadedMeta.copy(title = generated)
            }
        }
    }

    // Bead vn-edu.57: lazy-fetch the Canonical/Filed-to artifacts on first
    // open of that tab, keyed on [sessionStatus] too so a tab tapped before
    // the effect above resolves it re-fires once the real status lands.
    // Each artifact is fetched at most once per screen visit -- the null/
    // failure guards below skip a re-fetch on every later tab revisit.
    LaunchedEffect(selectedTab, sessionId, sessionStatus) {
        val status = sessionStatus ?: return@LaunchedEffect
        if (status != SessionStatus.INTEGRATED) return@LaunchedEffect
        when (selectedTab) {
            DetailTab.CANONICAL -> {
                if (canonicalTranscript == null && !canonicalLoadFailed) {
                    val text = app.fetchVaultSessionArtifact(sessionId, "transcript.json")
                    if (text != null) {
                        canonicalTranscript = CanonicalTranscriptParser.parse(text)
                    } else {
                        canonicalLoadFailed = true
                    }
                }
            }
            DetailTab.FILED_TO -> {
                if (filedToModel == null) {
                    val jsonText = app.fetchVaultSessionArtifact(sessionId, "organization.json")
                    // Only fetch the legacy markdown fallback when the JSON
                    // artifact doesn't exist/doesn't parse -- avoids a wasted
                    // second fetch for every post-schema session.
                    val mdText = if (OrganizationDocumentParser.parse(jsonText) == null) {
                        app.fetchVaultSessionArtifact(sessionId, "organization.md")
                    } else {
                        null
                    }
                    filedToModel = FiledToRenderModel.from(jsonText, mdText)
                }
            }
            DetailTab.LIVE_TEXT -> Unit
        }
    }

    val oggFile = remember(sessionId) { app.sessionStore.oggFile(app.sessionStore.sessionDir(sessionId)) }
    // Bead vn-edu.42: prefer the LLM-generated meta title over the derived
    // words the moment either one lands, without waiting for a recomposition
    // trigger beyond meta/transcriptLines themselves changing.
    val displayTitle = meta?.title?.takeIf { it.isNotBlank() } ?: DerivedTitle.from(transcriptLines.firstOrNull()?.text)

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Spacer(modifier = Modifier.height(12.dp))
                DetailHeader(title = displayTitle, uploadState = uploadState, onBack = onBack)
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
                        DetailTab.CANONICAL -> CanonicalTab(sessionStatus, canonicalTranscript, canonicalLoadFailed)
                        DetailTab.FILED_TO -> FiledToTab(sessionStatus, filedToModel)
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
private fun DetailHeader(title: String, uploadState: UploadState, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
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
            text = "No live transcript",
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
private fun LoadingTab() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Canonical tab (bead vn-edu.57): [status] null means "not yet resolved"
 * (show a spinner); a non-null [DetailTabPlaceholder.forStatus] result means
 * the session isn't INTEGRATED yet (show that truthful placeholder);
 * otherwise this is INTEGRATED and either mid-fetch ([transcript] still
 * null), failed ([loadFailed]), or ready to render.
 */
@Composable
private fun CanonicalTab(status: SessionStatus?, transcript: CanonicalTranscript?, loadFailed: Boolean) {
    val placeholder = status?.let { DetailTabPlaceholder.forStatus(it) }
    when {
        status == null -> LoadingTab()
        placeholder != null -> StubTab(placeholder)
        loadFailed -> StubTab(DetailTabPlaceholder.LOAD_FAILED)
        transcript == null -> LoadingTab()
        transcript.utterances.isEmpty() -> StubTab("No transcript content")
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(transcript.utterances) { utterance ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!utterance.speaker.isNullOrBlank()) {
                            Text(
                                text = "Speaker ${utterance.speaker}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = formatTranscriptTimestamp(utterance.t0Ms),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = utterance.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

/**
 * Filed-to tab (bead vn-edu.57): same not-yet-resolved / placeholder /
 * loading contract as [CanonicalTab], then dispatches on [FiledToRenderModel]
 * -- structured fragment cards for a post-schema session, raw markdown for a
 * pre-schema (legacy) one, or a load-failed message when neither artifact
 * came back.
 */
@Composable
private fun FiledToTab(status: SessionStatus?, model: FiledToRenderModel?) {
    val placeholder = status?.let { DetailTabPlaceholder.forStatus(it) }
    when {
        status == null -> LoadingTab()
        placeholder != null -> StubTab(placeholder)
        model == null -> LoadingTab()
        model is FiledToRenderModel.Unavailable -> StubTab(DetailTabPlaceholder.LOAD_FAILED)
        model is FiledToRenderModel.LegacyMarkdown -> LegacyOrganizationTab(model.text)
        model is FiledToRenderModel.Structured -> StructuredFiledToTab(model.document)
    }
}

@Composable
private fun LegacyOrganizationTab(markdownText: String) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = "Legacy format — organized before organization.json existed",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = markdownText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

// A regular scrollable Column rather than LazyColumn: a session's fragment
// count is small (single digits -- one per organize-skill topic fragment),
// and this way the totals/unassigned-spans footer is always composed, not
// dependent on LazyColumn's viewport-driven item composition scrolling it
// into view first.
@Composable
private fun StructuredFiledToTab(document: OrganizationDocument) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        document.fragments.forEach { fragment -> FragmentCard(fragment) }
        FiledToFooter(document.unassignedSpans, document.totals)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FragmentCard(fragment: OrganizationFragment) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${formatTranscriptTimestamp(fragment.tStartMs)} – ${formatTranscriptTimestamp(fragment.tEndMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = fragment.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (fragment.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fragment.tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag.name) })
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val destination = fragment.destination
            Text(
                text = if (destination != null) {
                    "${destination.path} (${if (destination.mode == "new_file") "new file" else "appended"})"
                } else {
                    "Excluded — not filed" + (fragment.notes?.let { ": $it" } ?: "")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FiledToFooter(unassignedSpans: List<OrganizationUnassignedSpan>, totals: OrganizationTotals?) {
    Column {
        if (unassignedSpans.isNotEmpty()) {
            Text(
                text = "Unassigned: " + unassignedSpans.joinToString("; ") {
                    "${formatTranscriptTimestamp(it.tStartMs)}–${formatTranscriptTimestamp(it.tEndMs)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (totals != null) {
            Text(
                text = "${totals.fragments} fragments · ${totals.notesCreated} created · " +
                    "${totals.notesAppended} appended · ${totals.excluded} excluded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
