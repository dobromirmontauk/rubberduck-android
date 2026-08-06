package com.montauk.voicecapture.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.montauk.voicecapture.duck.DuckStage
import com.montauk.voicecapture.duck.DuckState
import com.montauk.voicecapture.duck.ThoughtCloudWords
import com.montauk.voicecapture.duck.rememberReducedMotionEnabled
import com.montauk.voicecapture.duck.toDuckState
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.service.LatencyBadgeStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.SummaryStateHolder
import com.montauk.voicecapture.service.TagApprovalStateHolder
import com.montauk.voicecapture.service.TagsStateHolder
import com.montauk.voicecapture.service.TranscriptLine
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.service.TranscriptUiState
import com.montauk.voicecapture.service.crudeRecordingActivity
import com.montauk.voicecapture.stt.SttConnectionState
import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The full-glance recording screen. Bead asn-3sm's "layout A" (design board
 * section 2, the decided home state): minimal chrome -- a tiny rec/mode
 * indicator and the big timer up top, the animated duck stage filling the
 * rest, a large inset STOP button anchored to the bottom (bead vn-edu.32 --
 * a floating button with clear margins, not a full-bleed slab flush with
 * the screen edge). No bottom nav, no persistent notepad, no persistent
 * transcript -- this screen is meant to be readable at arm's length while
 * walking. (A "pause" control belongs in this same bottom chrome per the
 * design board, but bead asn-r60 owns that entire button + its pause/
 * auto-pause behavior on its own branch; this bead intentionally leaves it
 * out rather than build a second, conflicting one.)
 *
 * **Duck view vs. debug transcript view (bead asn-3sm).** The duck stage is
 * the default; double-tapping it swaps in the old-style live transcript
 * pane plus the demoted status chrome (Bluetooth/STT chips, loudness meter,
 * mode switcher) instead -- all pre-asn-3sm functionality, including the
 * transcript pane's own keyless message, unchanged, just relocated here.
 * Double-tapping again returns to the duck. [showDebugView] is plain
 * composable-local state: it resets to the duck view every fresh visit to
 * this screen, which is the expected default.
 *
 * The duck's LISTENING/SLEEPY state is derived via [crudeRecordingActivity]
 * -- a crude stand-in (silence hint -> QUIET, else SPEAKING) for asn-r60's
 * real `StateFlow<RecordingActivityState>` ([com.montauk.voicecapture.service.RecordingActivityStateHolder]
 * on that branch), which hasn't landed on `main` yet; neither paused
 * activity (and therefore SLEEPING) is reachable until it does. THINKING
 * briefly overrides whichever of those is current for [THINKING_DISPLAY_MS]
 * every time [SummaryStateHolder] publishes a fresh summary (design board:
 * "duck plays 'taking notes', then the notes card slides up") -- the
 * closest real signal available today; there's no equivalent signal yet for
 * a tag-scorer LLM call in flight.
 *
 * The thought cloud's BLUE/PURPLE/GREEN/WHITE split comes from
 * [ThoughtCloudWords.fromDisplayedTags], today's adapter over
 * [TagsStateHolder]'s [DisplayedTag] output plus [TagApprovalStateHolder]'s
 * session-local approvals; tapping a PURPLE word approves it (green +
 * haptic tick + the duck's happy-bounce, via [happyBounceTrigger]).
 *
 * [onOpenSettings] (bead vn-edu.46 superseding decision, extended by
 * vn-edu.66) is invoked when either keyless message is tapped: the
 * word-cloud message (visible directly on the duck view) deep-links to
 * Settings' "Word cloud & titles" key row ([ANTHROPIC_KEY_ROW_TEST_TAG]),
 * the transcript-pane message (in the debug view) to its "Live
 * transcription" key row ([ASSEMBLYAI_KEY_ROW_TEST_TAG]) -- both just
 * navigate to [SettingsScreen] itself (no in-screen scroll target exists
 * yet) rather than opening any dialog on this glance-mode screen.
 */
@Composable
fun RecordingScreen(onStopRecording: () -> Unit, onSetMode: (RecordingMode) -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val recordingState by RecordingStateHolder.state.collectAsStateWithLifecycle()
    val transcript by TranscriptStateHolder.state.collectAsStateWithLifecycle()
    val hasBluetoothMic = remember { hasBluetoothInputDevice(context) }
    val tags by TagsStateHolder.state.collectAsStateWithLifecycle()
    val summary by SummaryStateHolder.state.collectAsStateWithLifecycle()
    val latencyState by LatencyBadgeStateHolder.state.collectAsStateWithLifecycle()
    val approvedKeys by TagApprovalStateHolder.approvedKeys.collectAsStateWithLifecycle()
    val anthropicKeyConfigured = app.isAnthropicKeyConfigured()
    val assemblyKeyConfigured = app.isAssemblyKeyConfigured()
    var showDebugView by remember { mutableStateOf(false) }
    var happyBounceTrigger by remember { mutableStateOf(0) }
    val reducedMotion = rememberReducedMotionEnabled()

    // THINKING override window -- see the class KDoc's THINKING paragraph.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(THINKING_TICK_INTERVAL_MS)
        }
    }
    var thinkingUntilMs by remember { mutableStateOf(0L) }
    LaunchedEffect(summary.updatedAtMs) {
        if (summary.bullets.isNotEmpty()) thinkingUntilMs = System.currentTimeMillis() + THINKING_DISPLAY_MS
    }
    val baseDuckState = remember(transcript) { crudeRecordingActivity(transcript).toDuckState() }
    val duckState = if (nowMs < thinkingUntilMs) DuckState.THINKING else baseDuckState

    // Bead vn-edu.46's keyless guard, preserved: no key means NO word-cloud
    // data at all, even if TagsStateHolder is stale/non-empty (shouldn't
    // happen with NoOpTagScorer, but the UI gate must be on the key, not on
    // "is the list empty") -- matches the pre-asn-3sm TagChipsRow early return.
    val words = remember(tags, approvedKeys, anthropicKeyConfigured) {
        if (anthropicKeyConfigured) ThoughtCloudWords.fromDisplayedTags(tags, approvedKeys) else ThoughtCloudWords.EMPTY
    }

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(20.dp))
                MinimalTopChrome(mode = recordingState.mode, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BigTimer(elapsedMs = recordingState.elapsedMs)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(DUCK_TRANSCRIPT_TOGGLE_TEST_TAG)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { showDebugView = !showDebugView })
                        },
                ) {
                    if (showDebugView) {
                        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                            Spacer(modifier = Modifier.height(8.dp))
                            ChipsRow(transcript = transcript, hasBluetoothMic = hasBluetoothMic, recordingState = recordingState)
                            Spacer(modifier = Modifier.height(10.dp))
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
                        }
                    } else {
                        DuckStage(
                            duckState = duckState,
                            words = words,
                            reducedMotion = reducedMotion,
                            onApproveWord = { word ->
                                TagApprovalStateHolder.approve(word.text)
                                happyBounceTrigger++
                            },
                            summary = summary,
                            latencyState = latencyState,
                            onLatencyBadgeTap = {}, // asn-55q's L2 HUD opens here once that bead lands
                            happyBounceTrigger = happyBounceTrigger,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // Keyless word-cloud message stays on the default duck view (not
                // gated behind the double-tap toggle) -- mirrors the pre-asn-3sm
                // TagChipsRow keyless message 1:1 (bead vn-edu.46), just relocated.
                if (!showDebugView && !anthropicKeyConfigured) {
                    Text(
                        text = "(register your API key to see the word cloud)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .clickable(onClick = onOpenSettings)
                            .testTag(REGISTER_KEY_MESSAGE_TEST_TAG),
                    )
                }
                StopBar(modifier = Modifier.height(STOP_BAR_HEIGHT), onClick = onStopRecording)
            }
        }
    }
}

/** Test-only anchor for the duck-view/debug-transcript-view double-tap toggle (bead asn-3sm). */
const val DUCK_TRANSCRIPT_TOGGLE_TEST_TAG = "recording_duck_transcript_toggle"

private const val THINKING_TICK_INTERVAL_MS = 250L

/** How long THINKING overrides the duck's base state after a fresh summary lands (design board: "holds a few seconds"). */
const val THINKING_DISPLAY_MS = 1_800L

private val STOP_BAR_HEIGHT = 140.dp

/**
 * Layout A's minimal top chrome (design board section 2): a small "● REC"
 * indicator plus the current mode, replacing the pre-asn-3sm [ChipsRow] /
 * [LoudnessMeterBar] / [ModeSwitcher] row up here -- those move into the
 * double-tap debug view (see [RecordingScreen]) since the home state's
 * whole point is "duck + thought cloud, nothing else."
 */
@Composable
private fun MinimalTopChrome(mode: RecordingMode, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "● REC",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = mode.label().lowercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
 * mirrors the duck view's keyless word-cloud message (bead asn-3sm; see
 * [RecordingScreen]'s own [REGISTER_KEY_MESSAGE_TEST_TAG] usage) so the
 * reader understands *why* nothing is transcribing, distinct from
 * "recording, key present, just no speech yet" (which still renders the
 * normal "Listening…"/"(silence)" rows below via the unchanged keyed path).
 * Bead asn-3sm: this whole pane now lives in the double-tap debug view
 * rather than being always-visible -- unchanged otherwise.
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

/** Test-only anchor for the keyless word-cloud message (bead vn-edu.46, relocated onto the duck view by bead asn-3sm). */
const val REGISTER_KEY_MESSAGE_TEST_TAG = "recording_register_key_message"

/**
 * Large, deep-red STOP control rendered as an inset rounded button rather
 * than a full-bleed slab (bead vn-edu.32): [STOP_BUTTON_HORIZONTAL_MARGIN]
 * side margins and [navigationBarsPadding] plus a small bottom margin keep
 * it clear of the gesture-nav inset, so it reads as a button floating above
 * content rather than a bar replacing the (hidden) bottom nav. Still very
 * large -- full-width-minus-margins, at least [STOP_BUTTON_MIN_HEIGHT] tall.
 */
@Composable
private fun StopBar(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = STOP_BUTTON_HORIZONTAL_MARGIN, vertical = 8.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().heightIn(min = STOP_BUTTON_MIN_HEIGHT),
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

/**
 * Preview-only: the air gap above the STOP control and the control itself,
 * rendered standalone at phone-bottom proportions (bead vn-edu.32) so the
 * inset-rounded-button treatment (margins, corner radius, clearance from the
 * gesture-nav inset) can be inspected without needing the full recording
 * state or a device. Pre-asn-3sm this also previewed the topic chips row
 * directly above the button; that row is now the duck view's word cloud
 * (see `com.montauk.voicecapture.duck.DuckStagePreview`), so nothing
 * duck/word-cloud-related belongs in this bottom-of-screen preview anymore.
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
                Spacer(modifier = Modifier.height(24.dp))
            }
            StopBar(modifier = Modifier.weight(1f), onClick = {})
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
 * Preview-only: mode switcher and the bounded [LiveTranscriptPane] rendered
 * together with 30 finalized lines' worth of overlong fixture text (bead
 * vn-edu.37) -- demonstrates that the pane stays pinned to the newest line
 * and clips rather than overflows (this is the debug-view rendering; see
 * [RecordingScreen]'s KDoc for the duck-view/debug-view toggle).
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
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
