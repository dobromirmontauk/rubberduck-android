package com.montauk.voicecapture.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.audio.AudioRouteType
import com.montauk.voicecapture.audio.LoudnessVisualizer
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.service.RecordingActivityState
import com.montauk.voicecapture.service.RecordingActivityStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptLine
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.service.TranscriptUiState
import com.montauk.voicecapture.stt.SttConnectionState
import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.TagTier
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The full-glance recording screen: huge timer, LIVE/OFFLINE + Bluetooth
 * chips, a mic-level bar, the mode switcher, the last couple of transcript
 * lines, MAJOR-topic tag chips, and a large inset STOP button anchored to the bottom
 * (bead vn-edu.32 -- a floating button with clear margins, not a full-bleed
 * slab flush with the screen edge, which read as sitting exactly where the
 * app's own bottom nav normally lives). No bottom nav here -- this screen is
 * meant to be readable at arm's length while walking.
 *
 * [onOpenSettings] (bead vn-edu.46 superseding decision, extended by
 * vn-edu.66) is invoked when either keyless message is tapped: the tags-slot
 * message deep-links to Settings' "Word cloud & titles" key row
 * ([ANTHROPIC_KEY_ROW_TEST_TAG]), the transcript-pane message to its "Live
 * transcription" key row ([ASSEMBLYAI_KEY_ROW_TEST_TAG]) -- both just
 * navigate to [SettingsScreen] itself (no in-screen scroll target exists yet)
 * rather than opening any dialog on this glance-mode screen.
 */
@Composable
fun RecordingScreen(
    onStopRecording: () -> Unit,
    onSetMode: (RecordingMode) -> Unit = {},
    onSetPaused: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val recordingState by RecordingStateHolder.state.collectAsStateWithLifecycle()
    val transcript by TranscriptStateHolder.state.collectAsStateWithLifecycle()
    val activityState by RecordingActivityStateHolder.state.collectAsStateWithLifecycle()
    val hasBluetoothMic = remember { hasBluetoothInputDevice(context) }
    val tags by TagsStateHolder.state.collectAsStateWithLifecycle()
    val anthropicKeyConfigured = app.isAnthropicKeyConfigured()
    val assemblyKeyConfigured = app.isAssemblyKeyConfigured()

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(4f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(28.dp))
                    BigTimer(elapsedMs = recordingState.elapsedMs)
                    Spacer(modifier = Modifier.height(16.dp))
                    ChipsRow(transcript = transcript, hasBluetoothMic = hasBluetoothMic, recordingState = recordingState)
                    Spacer(modifier = Modifier.height(10.dp))
                    // Bead asn-r60: shown in place of nothing when SPEAKING/QUIET
                    // (renders null), so it never shifts the layout below it when
                    // there's nothing to say.
                    PauseBanner(
                        activityState = activityState,
                        quietDurationMs = transcript.quietDurationMs,
                        onResumeTapped = { onSetPaused(false) },
                    )
                    LoudnessMeterBar(level = transcript.micLevel, sessionId = recordingState.sessionId)
                    Spacer(modifier = Modifier.height(16.dp))
                    ModeSwitcher(currentMode = recordingState.mode, onSelect = onSetMode)
                    Spacer(modifier = Modifier.height(16.dp))
                    LiveTranscriptPane(
                        transcript = transcript,
                        assemblyKeyConfigured = assemblyKeyConfigured,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TagChipsRow(tags = tags, anthropicKeyConfigured = anthropicKeyConfigured, onRegisterKeyTapped = onOpenSettings)
                    // Extra air below the chips row (bead vn-edu.32) so the inset STOP
                    // button reads as floating above content, not touching the chips.
                    Spacer(modifier = Modifier.height(24.dp))
                }
                BottomActionsBar(
                    modifier = Modifier.weight(1f),
                    activityState = activityState,
                    onPauseToggle = { onSetPaused(activityState != RecordingActivityState.USER_PAUSED) },
                    onStop = onStopRecording,
                )
            }
        }
    }
}

/**
 * Bead asn-r60's spec, verbatim: 'Auto-paused (quiet 0:32) -- just start
 * talking, or tap to resume' for the soft/VAD-driven pause; a plainer
 * "Paused" indicator for the hard/manual one (the spec's "timer freezes with
 * explicit paused indicator" -- [BigTimer] above already freezes via
 * [RecordingUiState.elapsedMs]; this is the "indicator" half). Renders
 * nothing at all -- not even a zero-height placeholder -- for
 * [RecordingActivityState.SPEAKING]/[RecordingActivityState.QUIET], so the
 * layout doesn't reserve dead space while recording normally.
 */
@Composable
private fun PauseBanner(activityState: RecordingActivityState, quietDurationMs: Long, onResumeTapped: () -> Unit) {
    when (activityState) {
        RecordingActivityState.AUTO_PAUSED -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onResumeTapped)
                    .testTag(AUTO_PAUSE_BANNER_TEST_TAG),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "Auto-paused (quiet ${formatMinutesSeconds(quietDurationMs)}) -- just start talking, or tap to resume",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        RecordingActivityState.USER_PAUSED -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .testTag(USER_PAUSE_BANNER_TEST_TAG),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = "Paused -- tap Resume to keep recording",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        RecordingActivityState.SPEAKING, RecordingActivityState.QUIET -> Unit
    }
}

/** Test-only anchors for [PauseBanner] (bead asn-r60). */
const val AUTO_PAUSE_BANNER_TEST_TAG = "recording_auto_pause_banner"
const val USER_PAUSE_BANNER_TEST_TAG = "recording_user_pause_banner"

private fun formatMinutesSeconds(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

/**
 * Glance-safe Listen / Converse / Challenge segmented control. Listen is the
 * only enabled mode today; Converse and Challenge render visibly disabled
 * with a "soon" tag and never open a dialog -- tapping one just nudges (a
 * brief shake) since the glance-mode rule forbids dialogs while recording.
 * Enabled the whole time recording is live, per the mode-switcher spec: mode
 * is session state, switchable mid-session, not just at session start.
 */
@Composable
private fun ModeSwitcher(currentMode: RecordingMode, onSelect: (RecordingMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        RecordingMode.entries.forEach { mode ->
            ModeSegment(
                mode = mode,
                isActive = mode == currentMode,
                onSelect = onSelect,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun RecordingMode.label(): String = when (this) {
    RecordingMode.LISTEN -> "Listen"
    RecordingMode.CONVERSE -> "Converse"
    RecordingMode.CHALLENGE -> "Challenge"
}

@Composable
private fun ModeSegment(
    mode: RecordingMode,
    isActive: Boolean,
    onSelect: (RecordingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val nudgeOffset = remember { Animatable(0f) }

    val backgroundColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = when {
        isActive -> MaterialTheme.colorScheme.onPrimary
        mode.isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .offset { IntOffset(nudgeOffset.value.roundToInt(), 0) }
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .semantics { contentDescription = if (mode.isEnabled) mode.label() else "${mode.label()}, coming soon" }
            .clickable {
                if (mode.isEnabled) {
                    onSelect(mode)
                } else {
                    // Disabled modes never show a dialog while recording (glance-mode
                    // rule) -- a brief shake is the only feedback that the tap landed.
                    scope.launch {
                        nudgeOffset.animateTo(-6f, tween(40))
                        nudgeOffset.animateTo(6f, tween(80))
                        nudgeOffset.animateTo(0f, tween(60))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = mode.label(),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            )
            if (!mode.isEnabled) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "soon", style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp), color = contentColor)
            }
        }
    }
}

@Composable
private fun BigTimer(elapsedMs: Long) {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    Text(
        text = String.format("%02d:%02d", minutes, seconds),
        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * [recordingState] carries the live Bluetooth-routing status (bead vn-edu.2):
 * [RecordingUiState.activeAudioRoute]/[RecordingUiState.scoNarrowbandWarning]
 * update as [com.montauk.voicecapture.audio.MicAudioSource] re-routes
 * mid-session, unlike [hasBluetoothMic] (a one-time presence check taken at
 * screen-composition time) or [TranscriptUiState.sourceLabel] (fixed for the
 * whole session). [hasBluetoothMic] stays as the pre-routing fallback for
 * the brief window before the first route decision lands.
 */
@Composable
private fun ChipsRow(transcript: TranscriptUiState, hasBluetoothMic: Boolean, recordingState: RecordingUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SttStatusChip(transcript.connectionState)
        val (label, color) = when {
            // Debug-only audio injection (bead vn-edu.20) -- takes priority over
            // the live bluetooth/phone-mic detection below since there's no real
            // AudioRecord device to ask about while a file is standing in for one.
            transcript.sourceLabel == "FILE" -> "FILE" to Color(0xFFE0A93A)
            recordingState.scoNarrowbandWarning -> "BLUETOOTH (LOW QUALITY)" to Color(0xFFE8A33D)
            recordingState.activeAudioRoute == AudioRouteType.BLE_HEADSET ||
                recordingState.activeAudioRoute == AudioRouteType.BLUETOOTH_SCO -> "BLUETOOTH" to Color(0xFF5FBF6E)
            recordingState.activeAudioRoute == AudioRouteType.BUILTIN_MIC -> "PHONE MIC" to MaterialTheme.colorScheme.onSurfaceVariant
            hasBluetoothMic -> "BLUETOOTH" to Color(0xFF5FBF6E)
            else -> "PHONE MIC" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        StatusChip(label, color)
        // Sticky for the rest of the session once a Bluetooth device has
        // dropped out mid-recording (see RecordingUiState.bluetoothDeviceLost's
        // KDoc) -- a second, separate chip rather than folding into the one
        // above so the "what happened" signal survives even after the
        // BLUETOOTH/PHONE MIC chip above has already moved on.
        if (recordingState.bluetoothDeviceLost) {
            StatusChip("BT LOST → PHONE MIC", Color(0xFFE8A33D))
        }
    }
}

/**
 * Prominent voice-recorder-style loudness meter: a fixed row of vertical
 * bars scrolling left as new [LoudnessVisualizer] windows arrive, newest at
 * the right -- same visual-weight class as the chips row above it. Feeds off
 * the existing RMS tee ([com.montauk.voicecapture.audio.MicLevelMeter] via
 * [TranscriptUiState.micLevel]); opens no second AudioRecord. [sessionId]
 * keys the underlying [LoudnessVisualizer] so a new recording starts from an
 * empty history rather than carrying over the previous session's tail.
 */
@Composable
private fun LoudnessMeterBar(level: Float, sessionId: String?) {
    val visualizer = remember(sessionId) { LoudnessVisualizer() }
    var history by remember(visualizer) { mutableStateOf(visualizer.history) }
    LaunchedEffect(level, visualizer) {
        visualizer.onLevel(level)
        history = visualizer.history
    }
    LoudnessMeterBarContent(history = history)
}

/**
 * Pure rendering half of the loudness meter: draws [history] as a single row
 * of bars in one [Canvas] pass, so a ~100ms level update redraws one Canvas
 * rather than recomposing per-bar. Split out from [LoudnessMeterBar] so it
 * can be driven by synthetic history (below) for deterministic screenshots
 * that don't depend on live microphone input.
 */
@Composable
private fun LoudnessMeterBarContent(history: List<Float>) {
    val barColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.fillMaxWidth().height(LOUDNESS_METER_HEIGHT)) {
        val barCount = history.size
        if (barCount == 0) return@Canvas
        val gapPx = LOUDNESS_METER_BAR_GAP.toPx()
        val barWidth = ((size.width - gapPx * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val restHeightPx = LOUDNESS_METER_REST_HEIGHT.toPx().coerceAtMost(size.height)
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        history.forEachIndexed { index, level ->
            val activeHeight = (size.height - restHeightPx) * level.coerceIn(0f, 1f)
            val totalHeight = restHeightPx + activeHeight
            val left = index * (barWidth + gapPx)
            drawRoundRect(
                color = if (level > 0f) barColor else restColor,
                topLeft = Offset(left, size.height - totalHeight),
                size = Size(barWidth, totalHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

private val LOUDNESS_METER_HEIGHT = 56.dp
private val LOUDNESS_METER_BAR_GAP = 3.dp
private val LOUDNESS_METER_REST_HEIGHT = 4.dp

/** One rendered row of [LiveTranscriptPane], oldest-to-newest order matching [LazyColumn] item order. */
private sealed interface TranscriptRow {
    data class Final(val line: TranscriptLine) : TranscriptRow
    /**
     * The current open turn (bead vn-edu.45): [stableText] is the
     * word-level-final prefix, painted solid; [unstableTail] is the
     * still-forming remainder, painted dimmed. Either may be blank (a fresh
     * turn has no stable words yet; a turn about to close may have no
     * unstable tail left).
     */
    data class Partial(val stableText: String, val unstableTail: String) : TranscriptRow
    object Silence : TranscriptRow
    object Placeholder : TranscriptRow
}

/**
 * All finalized lines (large, solid) plus the current in-progress partial
 * -- its word-level-stable prefix solid, its still-forming tail dimmed
 * (bead vn-edu.45), so the reader sees words lock in as they're spoken
 * instead of the whole turn staying dimmed until `end_of_turn` (which can be
 * 45-60s+ into a continuous monologue) -- as the newest row. Or, if neither
 * has arrived in the last ~4s and the mic is quiet, a dimmed italic
 * "(silence)" row instead. A bare "Listening…" placeholder row covers the
 * empty-session case.
 *
 * [TranscriptUiState.partialStableText]/[TranscriptUiState.partialUnstableTail]
 * come from the STT backend's per-word finality when it's sent one; when
 * neither is populated but [TranscriptUiState.currentPartial] is non-blank
 * (no word-level data yet, or a caller that only sets the legacy field),
 * this falls back to treating the whole partial as the unstable tail --
 * exactly the pre-vn-edu.45 whole-partial-dimmed rendering.
 */
private fun transcriptRows(transcript: TranscriptUiState): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    transcript.finalLines.forEach { rows += TranscriptRow.Final(it) }
    when {
        transcript.currentPartial.isNotBlank() -> {
            val hasWordLevelSplit = transcript.partialStableText.isNotBlank() || transcript.partialUnstableTail.isNotBlank()
            rows += if (hasWordLevelSplit) {
                TranscriptRow.Partial(transcript.partialStableText, transcript.partialUnstableTail)
            } else {
                TranscriptRow.Partial(stableText = "", unstableTail = transcript.currentPartial)
            }
        }
        transcript.silenceHintVisible -> rows += TranscriptRow.Silence
    }
    if (rows.isEmpty()) rows += TranscriptRow.Placeholder
    return rows
}

/**
 * True when the newest row is scrolled fully into view -- i.e. nothing newer
 * is hidden below the fold. [rows] is laid out newest-first with
 * `reverseLayout = true` (bead vn-edu.43), so "newest" is index 0 and
 * "scrolled to newest" means the list hasn't scrolled away from that
 * reversed layout's start edge at all, matching [reverseLayout]'s definition
 * of `offset == 0` at index 0 -- see [LiveTranscriptPane]'s KDoc for why a
 * single overlong row makes the old non-reversed "is the last item's bottom
 * within the viewport" check insufficient on its own.
 */
private fun LazyListLayoutInfo.isScrolledToNewest(): Boolean {
    val first = visibleItemsInfo.firstOrNull() ?: return true
    return first.index == 0 && first.offset <= 0
}

/**
 * Bounded transcript pane between the mode switcher and the topic chips
 * row (bead vn-edu.37): a [LazyColumn] clips to its `weight(1f)` allotment
 * instead of the plain [Column] this used to be, so fast FILE-injected
 * playback can no longer grow the pane past its slot and starve the chips
 * row below it out of the layout.
 *
 * **Pinning the newest row's BOTTOM, not its TOP (bead vn-edu.43).** A turn
 * that stays open for 45-60s of continuous speech (no `end_of_turn`) renders
 * as a single PARTIAL row that can itself be taller than the pane -- the old
 * `reverseLayout = false` list scrolled that row's TOP edge to the viewport's
 * top (`animateScrollToItem(lastIndex)`'s default `scrollOffset = 0`), which
 * for a row shorter than the viewport pins it at the bottom (fine) but for a
 * row taller than the viewport instead clips its BOTTOM -- exactly the newest,
 * just-spoken words -- below the fold, permanently, until the turn finally
 * closes. Screen reads as frozen mid-sentence.
 *
 * `reverseLayout = true` with [rows] passed newest-first flips which edge is
 * the anchor: index 0 (now the newest row) is pinned to the list's start
 * edge, which `reverseLayout` places at the visual BOTTOM of the pane. A row
 * taller than the viewport still only shows a viewport's worth of itself, but
 * now that's measured from its own BOTTOM edge (the newest words, since
 * [Text] lays a paragraph out top-to-bottom) rather than its top -- the
 * oldest words of that one over-tall row scroll off above the fold instead.
 * `animateScrollToItem(0)` is therefore "scroll to newest" under this layout,
 * replacing the old `rows.lastIndex`.
 *
 * Auto-follow/snap-back is delegated to the pure [TranscriptFollowState]:
 * the pane scrolls to the newest row whenever that class says to, and only
 * a real user drag (tracked via [rememberLazyListState]'s
 * [androidx.compose.foundation.interaction.MutableInteractionSource], not
 * this composable's own programmatic scroll) is reported to it as a manual
 * scroll-away. That keeps the pane pinned to newest by default, lets a
 * reader scroll up to reread something, and snaps back to live once they've
 * been idle for [TranscriptFollowState.DEFAULT_IDLE_MS].
 *
 * Bead vn-edu.66: when [assemblyKeyConfigured] is false, none of the above
 * runs -- recording keeps capturing audio (per [com.montauk.voicecapture.stt.SttClientFactory],
 * a keyless build gets [com.montauk.voicecapture.stt.NoOpStreamingSttClient],
 * so [transcript] never gains a final line, partial, or even a silence hint
 * from a live STT session) and this slot instead shows the exact string
 * "(no transcription key)", dimmed and tappable via [onOpenSettings] --
 * mirrors the tags-slot keyless message ([TagChipsRow]) so the reader
 * understands *why* nothing is transcribing, distinct from "recording, key
 * present, just no speech yet" (which still renders the normal
 * "Listening…"/"(silence)" rows below via the unchanged keyed path).
 */
@Composable
private fun LiveTranscriptPane(
    transcript: TranscriptUiState,
    assemblyKeyConfigured: Boolean,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!assemblyKeyConfigured) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
            Text(
                text = "(no transcription key)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .testTag(NO_TRANSCRIPTION_KEY_MESSAGE_TEST_TAG),
            )
        }
        return
    }
    val rows = remember(
        transcript.finalLines,
        transcript.currentPartial,
        transcript.partialStableText,
        transcript.partialUnstableTail,
        transcript.silenceHintVisible,
    ) {
        transcriptRows(transcript)
    }
    val newestFirstRows = remember(rows) { rows.asReversed() }
    val listState = rememberLazyListState()
    val followState = remember { TranscriptFollowState() }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    // Only a real touch-drag counts as a manual scroll-away -- this excludes
    // the animateScrollToItem calls below, which move the same listState
    // without ever setting isDragged, so they can't be mistaken for the user
    // scrolling away from what they just asked to follow.
    LaunchedEffect(listState, isDragged) {
        if (isDragged) {
            snapshotFlow { listState.layoutInfo }.collect { layoutInfo ->
                followState.onManualScroll(System.currentTimeMillis(), layoutInfo.isScrolledToNewest())
            }
        }
    }

    LaunchedEffect(rows) {
        if (rows.isNotEmpty() && followState.onNewContent(System.currentTimeMillis())) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag(LIVE_TRANSCRIPT_PANE_TEST_TAG),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(newestFirstRows) { row -> TranscriptRowText(row) }
    }
}

/** Exposed for [com.montauk.voicecapture.ui.LiveTranscriptPaneOverlongPartialTest]'s geometric pinning assertions. */
internal const val LIVE_TRANSCRIPT_PANE_TEST_TAG = "live-transcript-pane"

/** Test-only anchor for the keyless transcript-pane message (bead vn-edu.66). */
const val NO_TRANSCRIPTION_KEY_MESSAGE_TEST_TAG = "recording_no_transcription_key_message"

@Composable
private fun TranscriptRowText(row: TranscriptRow) {
    when (row) {
        is TranscriptRow.Final -> Text(
            text = row.line.text,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        is TranscriptRow.Partial -> {
            val stableColor = MaterialTheme.colorScheme.onBackground
            val unstableColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            Text(
                text = partialAnnotatedString(row.stableText, row.unstableTail, stableColor, unstableColor),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        TranscriptRow.Silence -> Text(
            text = "(silence)",
            style = MaterialTheme.typography.headlineMedium.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        TranscriptRow.Placeholder -> Text(
            text = "Listening…",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Bead vn-edu.45: one [AnnotatedString] spanning the still-open turn's
 * word-level-stable prefix (solid, [stableColor]) and still-forming
 * [unstableTail] (dimmed, [unstableColor]) -- a single [Text] with two
 * [SpanStyle]s rather than two separate [Text] composables so the words
 * keep flowing as one continuously word-wrapped paragraph (two composables
 * would force a hard line break between them even when there's room to
 * keep going on the same visual line, and would also reintroduce the
 * single-tall-item-taller-than-viewport risk vn-edu.43 fixed if either half
 * grew large on its own).
 */
internal fun partialAnnotatedString(
    stableText: String,
    unstableTail: String,
    stableColor: Color,
    unstableColor: Color,
): AnnotatedString = buildAnnotatedString {
    if (stableText.isNotBlank()) {
        withStyle(SpanStyle(color = stableColor)) { append(stableText) }
    }
    if (stableText.isNotBlank() && unstableTail.isNotBlank()) append(" ")
    if (unstableTail.isNotBlank()) {
        withStyle(SpanStyle(color = unstableColor)) { append(unstableTail) }
    }
}

/**
 * Up to 3 confidence-ranked MAJOR-topic chips (bead vn-edu.38, supersedes
 * the v1 frequency-chip cloud) -- [tags] is [TagsStateHolder]'s current
 * displayed set, already ranked and size-classed by
 * [com.montauk.voicecapture.tags.TagTracker].
 *
 * Each of the 3 rank slots is its own [AnimatedContent] keyed on *what
 * currently occupies that slot* rather than on tag identity: a rank change
 * (a stronger candidate displacing a weaker one, or a tag simply climbing
 * from rank 2 to rank 1) reads as that slot's chip shrinking/fading out and
 * the new one growing/fading in, which is the "shrink away, replaced by a
 * stronger candidate" motion the spec asks for -- without needing a full
 * move-animation API for what is only ever a 3-item list. [Modifier.animateContentSize]
 * on the row absorbs the width change as chips of different size classes
 * enter/exit. Durations are short (160-220ms) and opacity/scale-only --
 * deliberately subtle rather than a bouncy/springy default, per the
 * reduced-motion guidance.
 *
 * Bead vn-edu.46 superseding decision (2026-08-05): when
 * [anthropicKeyConfigured] is false, no chips render at all -- not even an
 * empty row -- because [com.montauk.voicecapture.tags.NoOpTagScorer] never
 * computes any tags in that state (no heuristic guess). Instead this slot
 * shows the exact string "(register your API key to see the word cloud)",
 * dimmed and tappable, invoking [onRegisterKeyTapped] (deep-links to
 * Settings' "Word cloud & titles" key row) -- so a user always understands
 * *why* there's nothing here, distinct from "no topic has scored high
 * enough yet" (which, when keyed, still renders this same empty row via the
 * early return below).
 */
@Composable
private fun TagChipsRow(tags: List<DisplayedTag>, anthropicKeyConfigured: Boolean, onRegisterKeyTapped: () -> Unit = {}) {
    if (!anthropicKeyConfigured) {
        Text(
            text = "(register your API key to see the word cloud)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRegisterKeyTapped)
                .testTag(REGISTER_KEY_MESSAGE_TEST_TAG),
        )
        return
    }
    if (tags.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (slot in 0 until MAX_DISPLAYED_TAG_SLOTS) {
            key(slot) {
                AnimatedContent(
                    targetState = tags.getOrNull(slot),
                    transitionSpec = {
                        (fadeIn(tween(TAG_CHIP_ENTER_MS)) + scaleIn(initialScale = TAG_CHIP_SCALE_FROM, animationSpec = tween(TAG_CHIP_ENTER_MS)))
                            .togetherWith(fadeOut(tween(TAG_CHIP_EXIT_MS)) + scaleOut(targetScale = TAG_CHIP_SCALE_FROM, animationSpec = tween(TAG_CHIP_EXIT_MS)))
                    },
                    label = "tag-slot-$slot",
                ) { tag ->
                    if (tag != null) TagChip(tag)
                }
            }
        }
    }
}

/** Test-only anchor for the keyless tags-slot message (bead vn-edu.46). */
const val REGISTER_KEY_MESSAGE_TEST_TAG = "recording_register_key_message"

private const val MAX_DISPLAYED_TAG_SLOTS = 3
private const val TAG_CHIP_ENTER_MS = 220
private const val TAG_CHIP_EXIT_MS = 160
private const val TAG_CHIP_SCALE_FROM = 0.85f

/**
 * Bead vn-edu.47: a tree-anchored scorer's rare new-topic proposal
 * ([DisplayedTag.isProposal]) renders visually subtle -- a dashed border
 * rather than [TagChip]'s normal solid-fill chip -- so it reads as "not
 * confirmed yet" without the STOP-adjacent glance screen needing any extra
 * text to explain why. A tree-matched or legacy free-form tag (the vast
 * majority) is unaffected. [PROPOSAL_TAG_CHIP_TEST_TAG]/[MATCHED_TAG_CHIP_TEST_TAG]
 * let a Robolectric test assert on this distinction without needing pixel
 * comparison of the dashed stroke itself.
 */
@Composable
private fun TagChip(tag: DisplayedTag) {
    val (fontSize, verticalPadding) = when (tag.tier) {
        TagTier.PRIMARY -> 18.sp to 10.dp
        TagTier.SECONDARY -> 15.sp to 8.dp
        TagTier.TERTIARY -> 13.sp to 6.dp
    }
    val shape = RoundedCornerShape(999.dp)
    val proposalBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val proposalBorder = Modifier.drawWithContent {
        drawContent()
        drawRoundRect(
            color = proposalBorderColor,
            cornerRadius = CornerRadius(size.minDimension / 2f),
            style = Stroke(width = PROPOSAL_BORDER_WIDTH_DP.dp.toPx(), pathEffect = PathEffect.dashPathEffect(PROPOSAL_DASH_PATTERN)),
        )
    }
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .then(if (tag.isProposal) proposalBorder else Modifier)
            .testTag(if (tag.isProposal) PROPOSAL_TAG_CHIP_TEST_TAG else MATCHED_TAG_CHIP_TEST_TAG)
            .padding(horizontal = 14.dp, vertical = verticalPadding),
    ) {
        Text(
            text = tag.tag,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Test-only anchors for [TagChip]'s proposal-vs-matched styling (bead vn-edu.47). */
const val PROPOSAL_TAG_CHIP_TEST_TAG = "recording_tag_chip_proposal"
const val MATCHED_TAG_CHIP_TEST_TAG = "recording_tag_chip_matched"
private const val PROPOSAL_BORDER_WIDTH_DP = 1.5f
private val PROPOSAL_DASH_PATTERN = floatArrayOf(9f, 6f)

/**
 * Bead asn-r60: the large, deep-red STOP control (bead vn-edu.32's inset
 * rounded button, unchanged in size/color/position) now shares its row with
 * a smaller Pause/Resume button "next to Stop" per the bead's spec.
 * [STOP_BUTTON_HORIZONTAL_MARGIN] side margins and [navigationBarsPadding]
 * plus a small bottom margin keep the whole row clear of the gesture-nav
 * inset, same as the old single-button [STOP_BUTTON_MIN_HEIGHT]-tall bar.
 * The Pause button's label flips to "RESUME" while
 * [RecordingActivityState.USER_PAUSED] -- see [RecordingScreen]'s
 * `onPauseToggle` for why that's the only state this label depends on
 * (tapping while auto-paused escalates to a hard pause, so it still reads
 * "PAUSE" there, not "RESUME").
 */
@Composable
private fun BottomActionsBar(
    modifier: Modifier = Modifier,
    activityState: RecordingActivityState,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit,
) {
    val isUserPaused = activityState == RecordingActivityState.USER_PAUSED
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = STOP_BUTTON_HORIZONTAL_MARGIN, vertical = 8.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPauseToggle,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = STOP_BUTTON_MIN_HEIGHT)
                    .testTag(PAUSE_RESUME_BUTTON_TEST_TAG),
                shape = RoundedCornerShape(STOP_BUTTON_CORNER_RADIUS),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    text = if (isUserPaused) "RESUME" else "PAUSE",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Button(
                onClick = onStop,
                modifier = Modifier.weight(2f).fillMaxHeight().heightIn(min = STOP_BUTTON_MIN_HEIGHT),
                shape = RoundedCornerShape(STOP_BUTTON_CORNER_RADIUS),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(
                    text = "STOP",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}

/** Test-only anchor for [BottomActionsBar]'s Pause/Resume button (bead asn-r60). */
const val PAUSE_RESUME_BUTTON_TEST_TAG = "recording_pause_resume_button"

private val STOP_BUTTON_HORIZONTAL_MARGIN = 16.dp
private val STOP_BUTTON_CORNER_RADIUS = 20.dp
private val STOP_BUTTON_MIN_HEIGHT = 72.dp

/**
 * Deterministic stand-in for real speech, used only by the previews below:
 * feeds a synthetic sequence of RMS windows (silence, then a rising/falling
 * "loud speech" hump) through the real [LoudnessVisualizer] so the resulting
 * history exercises the actual attack/decay/quantization pipeline rather
 * than hand-drawn bar heights.
 */
private fun syntheticLoudHistory(): List<Float> {
    val visualizer = LoudnessVisualizer()
    val syntheticRms = listOf(0f, 0f, 0.05f, 0.15f, 0.35f, 0.25f, 0.5f, 0.3f, 0.6f, 0.2f, 0.45f, 0.7f, 0.15f, 0.4f)
    repeat(LoudnessVisualizer.DEFAULT_HISTORY_LENGTH) { i ->
        visualizer.onLevel(syntheticRms[i % syntheticRms.size])
    }
    return visualizer.history
}

/**
 * Preview-only: the meter at silence. No real microphone involved -- this
 * renders [LoudnessMeterBarContent] directly against an all-zero history,
 * which is exactly what a silent recording converges to.
 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun LoudnessMeterBarPreviewQuiet() {
    VoiceCaptureTheme {
        LoudnessMeterBarContent(history = List(LoudnessVisualizer.DEFAULT_HISTORY_LENGTH) { 0f })
    }
}

/**
 * Preview-only: the meter fed a synthetic "loud speech" history (see
 * [syntheticLoudHistory]) so the bar shapes/motion can be inspected and
 * screenshotted without needing live audio.
 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun LoudnessMeterBarPreviewLoud() {
    VoiceCaptureTheme {
        LoudnessMeterBarContent(history = syntheticLoudHistory())
    }
}

/** Synthetic tag chips, most confident first, for the bottom-of-screen preview below. */
private fun syntheticTags(): List<DisplayedTag> = listOf(
    DisplayedTag("budget", 0.9, rank = 1, tier = TagTier.PRIMARY),
    DisplayedTag("timeline", 0.65, rank = 2, tier = TagTier.SECONDARY),
    DisplayedTag("vendor", 0.55, rank = 3, tier = TagTier.SECONDARY),
)

/**
 * Preview-only: the bottom of [RecordingScreen] -- topic chips, the air gap
 * below them, and the Pause + STOP row -- rendered standalone at phone-bottom
 * proportions (bead vn-edu.32's inset-rounded-button treatment, extended by
 * bead asn-r60's Pause button) so margins/corner radius/gesture-nav
 * clearance can be inspected without needing the full recording state or a
 * device.
 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 280)
@Composable
private fun StopBarPreview() {
    VoiceCaptureTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                TagChipsRow(tags = syntheticTags(), anthropicKeyConfigured = true)
                Spacer(modifier = Modifier.height(24.dp))
            }
            BottomActionsBar(
                modifier = Modifier.weight(1f),
                activityState = RecordingActivityState.SPEAKING,
                onPauseToggle = {},
                onStop = {},
            )
        }
    }
}

/**
 * Synthetic overlong transcript for [LiveTranscriptPaneOverlongPreview]: far
 * more finalized lines than could ever fit the pane's bounded height, plus a
 * current partial, standing in for fast FILE-injected playback (bead
 * vn-edu.37's repro) without needing a device or a live STT session.
 */
private fun syntheticOverlongTranscript(): TranscriptUiState {
    val finalLines = (1..30).map { i ->
        TranscriptLine(
            text = "Finalized line $i -- a long finalized utterance that wraps across " +
                "several visual lines, exercising the bounded pinned-to-newest pane " +
                "instead of an unbounded block that swallows the chips row below it.",
            startMs = i * 1_000L,
            endMs = i * 1_000L + 900L,
        )
    }
    return TranscriptUiState(
        connectionState = SttConnectionState.CONNECTED,
        finalLines = finalLines,
        currentPartial = "and this current partial line is still being spoken, dimmed, " +
            "sitting at the very bottom where the reader's eye rests",
        sourceLabel = "FILE",
    )
}

/**
 * Preview-only: mode switcher, the bounded [LiveTranscriptPane], and the
 * topic chips row rendered together with 30 finalized lines' worth of
 * overlong fixture text (bead vn-edu.37) -- demonstrates that the pane stays
 * pinned to the newest line, clips rather than overflows, and the chips row
 * underneath stays fully visible instead of being starved out of the layout.
 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun LiveTranscriptPaneOverlongPreview() {
    VoiceCaptureTheme {
        val transcript = remember { syntheticOverlongTranscript() }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(16.dp))
            ModeSwitcher(currentMode = RecordingMode.LISTEN, onSelect = {})
            Spacer(modifier = Modifier.height(16.dp))
            LiveTranscriptPane(transcript = transcript, assemblyKeyConfigured = true, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(16.dp))
            TagChipsRow(tags = syntheticTags(), anthropicKeyConfigured = true)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Synthetic single-partial transcript for [LiveTranscriptPaneGiantPartialPreview]:
 * no finalized lines at all, just one very long continuously-growing PARTIAL
 * (bead vn-edu.43's exact repro -- a turn that stays open for 45-60s of
 * continuous speech never gets to hand any of it off to [TranscriptRow.Final],
 * so the *entire* transcript so far is this one row).
 */
private fun syntheticGiantPartialTranscript(): TranscriptUiState = TranscriptUiState(
    connectionState = SttConnectionState.CONNECTED,
    currentPartial = (1..80).joinToString(" ") { i ->
        "spoken-word-$i"
    } + " ...and this is the newest word just spoken, which must stay visible",
    sourceLabel = "FILE",
)

/**
 * Preview-only: the exact shape of bead vn-edu.43's bug report -- a single
 * PARTIAL row, on its own, taller than the pane's bounded viewport (no
 * finalized lines to share the space with, unlike [LiveTranscriptPaneOverlongPreview]'s
 * many-final-lines scenario). Confirms the newest words (end of the
 * `currentPartial` string) render at the bottom of the pane, not clipped
 * off below it.
 */
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun LiveTranscriptPaneGiantPartialPreview() {
    VoiceCaptureTheme {
        val transcript = remember { syntheticGiantPartialTranscript() }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(16.dp))
            ModeSwitcher(currentMode = RecordingMode.LISTEN, onSelect = {})
            Spacer(modifier = Modifier.height(16.dp))
            LiveTranscriptPane(transcript = transcript, assemblyKeyConfigured = true, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(16.dp))
            TagChipsRow(tags = syntheticTags(), anthropicKeyConfigured = true)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
