package com.montauk.voicecapture.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.montauk.voicecapture.service.RecordingActivityState
import com.montauk.voicecapture.service.RecordingActivityStateHolder
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.RecordingUiState
import com.montauk.voicecapture.service.SummaryStateHolder
import com.montauk.voicecapture.service.TagRailStateHolder
import com.montauk.voicecapture.service.TagTreeStateHolder
import com.montauk.voicecapture.service.TranscriptLine
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.service.TranscriptUiState
import com.montauk.voicecapture.stt.SttConnectionState
import com.montauk.voicecapture.tags.RailChipSource
import com.montauk.voicecapture.tags.TagFilingDestination
import com.montauk.voicecapture.tags.TagRailChip
import com.montauk.voicecapture.tags.TagStatus
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The full-glance recording screen. Bead asn-3sm's "layout A" (design board
 * section 2, the decided home state): minimal chrome -- a tiny rec/mode/
 * pause indicator and the big timer up top, the animated duck stage filling
 * the rest, a large inset STOP button (plus, per team-lead's v2 pause
 * redesign, a floating Pause/Resume pill next to it) anchored to the bottom
 * (bead vn-edu.32 -- a floating button with clear margins, not a full-bleed
 * slab flush with the screen edge). No bottom nav, no persistent notepad, no
 * persistent transcript -- this screen is meant to be readable at arm's
 * length while walking. Bead asn-r60 owns the pause STATE MACHINE
 * ([RecordingActivityStateHolder]/[onSetPaused]); this bead owns all of the
 * pause PRESENTATION on this screen, which supersedes asn-r60's own interim
 * `PauseBanner`/`BottomActionsBar` UI outright (see [MinimalTopChrome] and
 * [PauseResumeChip]) -- there is no separate banner anywhere on this screen
 * by design; the duck falling asleep, the Z-trail, this pill, and the frozen
 * big timer above are the entire pause presentation.
 *
 * **Duck view vs. debug transcript view (bead asn-3sm).** The duck stage is
 * the default; double-tapping it swaps in the old-style live transcript
 * pane, the demoted status chrome (Bluetooth/STT chips, loudness meter,
 * mode switcher), and -- as of bead asn-45m -- the editable tag rail +
 * filing-destination ribbon ([TagRailSection], previously always-visible
 * above the transcript pane; the duck view's [ThoughtCloud] is now the
 * primary, always-visible tag surface, so the rail moves in alongside the
 * rest of this screen's pre-asn-3sm debug chrome instead). Double-tapping
 * again returns to the duck. [showDebugView] is plain composable-local
 * state: it resets to the duck view every fresh visit to this screen, which
 * is the expected default. The pause pill lives only in the duck view's
 * floating controls -- there is no pause affordance in the debug view.
 *
 * The duck's LISTENING/SLEEPY/SLEEPING state is derived directly from
 * [RecordingActivityStateHolder]'s real `StateFlow<RecordingActivityState>`
 * (bead asn-r60) via [toDuckState] -- this superseded a crude
 * transcript-derived stand-in (`crudeRecordingActivity`) once asn-r60 landed
 * on `main`. THINKING briefly overrides whichever of those is current for
 * [THINKING_DISPLAY_MS] every time [SummaryStateHolder] publishes a fresh
 * summary (design board: "duck plays 'taking notes', then the notes card
 * slides up") -- the closest real signal available today; there's no
 * equivalent signal yet for a tag-scorer LLM call in flight.
 *
 * The thought cloud's BLUE/PURPLE/GREEN/WHITE split comes from
 * [ThoughtCloudWords.fromTagRailChips] over the same [rail][TagRailStateHolder]
 * [TagRailSection] renders in the debug view -- one shared list, two
 * renderings (see [TagRailChip]'s own KDoc for why its status/approved
 * fields already carry this word cloud's exact color split). Tapping a
 * PURPLE word calls [onApproveTag] (green + haptic tick + the duck's
 * happy-bounce, via [happyBounceTrigger]) -- the same callback
 * [TagRailSection]'s own tap-to-approve uses, so approving from either
 * surface converges on the same state.
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
fun RecordingScreen(
    onStopRecording: () -> Unit,
    onSetMode: (RecordingMode) -> Unit = {},
    onAddTag: (tag: String, tagId: String?) -> Unit = { _, _ -> },
    onRemoveTag: (tag: String) -> Unit = {},
    onSwapTag: (oldTag: String, newTag: String, newTagId: String?) -> Unit = { _, _, _ -> },
    onApproveTag: (tag: String) -> Unit = {},
    onSetPaused: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    val recordingState by RecordingStateHolder.state.collectAsStateWithLifecycle()
    val transcript by TranscriptStateHolder.state.collectAsStateWithLifecycle()
    val activityState by RecordingActivityStateHolder.state.collectAsStateWithLifecycle()
    val hasBluetoothMic = remember { hasBluetoothInputDevice(context) }
    val rail by TagRailStateHolder.state.collectAsStateWithLifecycle()
    // Bead asn-45m: read-only here -- RecordingService is the only thing
    // that ever calls VoiceCaptureApp.currentTagTree() (a real,
    // network-capable call); this screen just reads whatever it last
    // resolved, defaulting to TagTree.EMPTY (free-form fallback) until then.
    // See TagTreeStateHolder's KDoc for why that split matters for tests.
    val tagTree by TagTreeStateHolder.state.collectAsStateWithLifecycle()
    val summary by SummaryStateHolder.state.collectAsStateWithLifecycle()
    val latencyState by LatencyBadgeStateHolder.state.collectAsStateWithLifecycle()
    val anthropicKeyConfigured = app.isAnthropicKeyConfigured()
    val assemblyKeyConfigured = app.isAssemblyKeyConfigured()
    var pickerRequest by remember { mutableStateOf<TagPickerRequest?>(null) }
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
    // Bead asn-3h6: NOT `remember(activityState)` anymore -- DROWSY depends
    // on transcript.autoPauseFillFraction too, which changes continuously
    // while activityState stays QUIET, so the mapping has to re-run on every
    // transcript update, not just on activityState transitions.
    val baseDuckState = activityState.toDuckState(transcript.autoPauseFillFraction)
    val duckState = if (nowMs < thinkingUntilMs) DuckState.THINK else baseDuckState

    // Bead vn-edu.46's keyless guard, preserved: no key means NO word-cloud
    // data at all, even if TagRailStateHolder is stale/non-empty (shouldn't
    // happen -- a keyless NoOpTagScorer never suggests anything, and this
    // doesn't trust that alone; matches TagRailSection's own visibleRail
    // filter for the debug view's rendering of the identical rail).
    val words = remember(rail, anthropicKeyConfigured) {
        if (anthropicKeyConfigured) ThoughtCloudWords.fromTagRailChips(rail) else ThoughtCloudWords.EMPTY
    }

    VoiceCaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(20.dp))
                // Bead v5.1: the "● REC"/"⏸ auto"/"⏸ paused" top-chrome
                // labels are debug-view-only now -- the duck view drops them
                // entirely in every state (see this file's class KDoc and
                // MinimalTopChrome's own KDoc). The debug view still wants
                // them, so they render right where they always have, just
                // gated on showDebugView instead of unconditionally.
                if (showDebugView) {
                    MinimalTopChrome(mode = recordingState.mode, activityState = activityState, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // Device-test directive (2026-08-06 drop #1, item d): the
                // timer itself is the pause tell -- warm/red only while
                // actually recording, grey+frozen the instant either pause
                // kind takes over (matches the storyboard's dim `.paused`
                // timer style, frames 10-11).
                val isPausedForTimer = activityState == RecordingActivityState.AUTO_PAUSED ||
                    activityState == RecordingActivityState.USER_PAUSED
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BigTimer(elapsedMs = recordingState.elapsedMs, isPaused = isPausedForTimer)
                }
                // Bead asn-kd2 v5.2: the mic-loudness waveform, restored
                // under the timer in every duck-view state (see
                // DuckWaveformBar's own KDoc for the three renderings) --
                // the debug view keeps its own richer LoudnessMeterBar
                // further down instead, so this only renders on the duck
                // side of the toggle. Device-test directive (drop #1, item
                // 4): this was the very first thing the live tester looked
                // for -- kept full-width (no side padding) and tall enough
                // to read at a glance rather than disappearing under the
                // timer.
                if (!showDebugView) {
                    Spacer(modifier = Modifier.height(10.dp))
                    DuckWaveformBar(
                        activityState = activityState,
                        micLevel = transcript.micLevel,
                        sessionId = recordingState.sessionId,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                            // Bead asn-45m: the editable tag rail + filing-destination
                            // ribbon -- demoted into this debug view by bead asn-3sm
                            // (see the class KDoc); still "pinned above the live
                            // transcript" per the original bead spec, just within
                            // this view rather than always-visible.
                            TagRailSection(
                                rail = rail,
                                anthropicKeyConfigured = anthropicKeyConfigured,
                                destination = TagFilingDestination.destinationFor(rail.firstOrNull(), tagTree),
                                onRegisterKeyTapped = onOpenSettings,
                                onRemove = onRemoveTag,
                                onRequestAdd = { pickerRequest = TagPickerRequest.Add },
                                onRequestSwap = { oldTag -> pickerRequest = TagPickerRequest.Swap(oldTag) },
                                onApprove = onApproveTag,
                            )
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
                                onApproveTag(word.text)
                                happyBounceTrigger++
                            },
                            summary = summary,
                            latencyState = latencyState,
                            onLatencyBadgeTap = {}, // asn-55q's L2 HUD opens here once that bead lands
                            happyBounceTrigger = happyBounceTrigger,
                            modifier = Modifier.fillMaxSize(),
                            // Design-board addendum: controls float ON the duck in this
                            // view (z-order above him, overlapping his lower body) --
                            // DuckStage places this slot itself. The debug view below
                            // renders the identical StopBar as a normal, non-overlapping
                            // bottom row instead (there's no duck to float over there).
                            // Bead asn-r60/asn-3sm: pause presentation lives entirely
                            // here now -- no separate banner (v2 dropped it outright;
                            // the duck falling asleep + Z-trail + this button + the
                            // frozen timer above is the whole story) and no non-
                            // floating equivalent in the debug view (there is no other
                            // pause UI anywhere on this screen by design).
                            // Device-test directive (drop #1, item b): the
                            // pause/resume-role pill is an IN-PLACE swap in
                            // the bottom-LEFT slot -- not a centered group
                            // next to STOP. SpaceBetween over the full
                            // control-row width pins it to the start edge
                            // and STOP to the end edge (bottom-right,
                            // prominent), matching the storyboard.
                            controls = {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PauseResumeChip(
                                        activityState = activityState,
                                        fillFraction = transcript.autoPauseFillFraction,
                                        onSetPaused = onSetPaused,
                                    )
                                    StopBar(onClick = onStopRecording, floating = true)
                                }
                            },
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
                if (showDebugView) {
                    StopBar(modifier = Modifier.height(STOP_BAR_HEIGHT), onClick = onStopRecording, floating = false)
                }
            }
        }

        pickerRequest?.let { request ->
            TagPickerSheet(
                tree = tagTree,
                onPick = { tag, tagId ->
                    when (request) {
                        is TagPickerRequest.Add -> onAddTag(tag, tagId)
                        is TagPickerRequest.Swap -> onSwapTag(request.oldTag, tag, tagId)
                    }
                    pickerRequest = null
                },
                onDismiss = { pickerRequest = null },
            )
        }
    }
}

/** Test-only anchor for the duck-view/debug-transcript-view double-tap toggle (bead asn-3sm). */
const val DUCK_TRANSCRIPT_TOGGLE_TEST_TAG = "recording_duck_transcript_toggle"

private const val THINKING_TICK_INTERVAL_MS = 250L

/** How long THINKING overrides the duck's base state after a fresh summary lands (design board: "holds a few seconds"). */
const val THINKING_DISPLAY_MS = 1_800L

private val STOP_BAR_HEIGHT = 140.dp

/** Which affordance opened [TagPickerSheet] -- decides whether a pick becomes an add or a swap of a specific existing chip. */
private sealed interface TagPickerRequest {
    object Add : TagPickerRequest
    data class Swap(val oldTag: String) : TagPickerRequest
}

/**
 * The debug view's top chrome (design board section 2, superseded by v5.1):
 * a small "● REC"/"⏸ auto"/"⏸ paused" indicator plus the current mode,
 * replacing the pre-asn-3sm [ChipsRow] / [LoudnessMeterBar] / [ModeSwitcher]
 * row up here. [activityState] (bead asn-r60) drives which of the three the
 * left-hand indicator shows.
 *
 * **Debug-view-only as of bead v5.1.** Earlier this rendered unconditionally
 * above both the duck and debug views; the locked no-corner-labels rule now
 * extends to this row too -- the duck view shows it in NO state (not even
 * the pause states' "⏸ auto"/"⏸ paused" text: the sleeping duck, the
 * frozen timer, and the floating Resume pill already say everything there
 * is to say). [RecordingScreen] only calls this composable inside its
 * `showDebugView` branch now; the pause/resume presentation itself is
 * unaffected (team-lead's v2 redesign already superseded asn-r60/asn-o63's
 * own `PauseBanner`/`BottomActionsBar` UI outright -- see
 * [PauseResumeChip]): no separate banner anywhere on this screen, just the
 * floating pause/resume pill next to STOP.
 */
@Composable
private fun MinimalTopChrome(mode: RecordingMode, activityState: RecordingActivityState, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val (label, color) = when (activityState) {
            RecordingActivityState.AUTO_PAUSED -> "⏸ auto" to MaterialTheme.colorScheme.onSurfaceVariant
            RecordingActivityState.USER_PAUSED -> "⏸ paused" to MaterialTheme.colorScheme.error
            RecordingActivityState.SPEAKING, RecordingActivityState.QUIET -> "● REC" to MaterialTheme.colorScheme.error
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
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

/**
 * [isPaused] (device-test directive, drop #1 item d): grey+frozen during
 * either pause kind -- the storyboard's own `.paused` timer style (frames
 * 10-11) -- warm/[MaterialTheme.colorScheme.primary] only while actually
 * recording. The elapsed value itself already freezes independently
 * (real recorded-content time, never wall clock -- see [RecordingUiState.elapsedMs]);
 * this only controls the color, not whether the number keeps ticking.
 */
@Composable
private fun BigTimer(elapsedMs: Long, isPaused: Boolean) {
    val totalSeconds = elapsedMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    Text(
        text = String.format("%02d:%02d", minutes, seconds),
        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
        color = if (isPaused) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
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

/**
 * Bead asn-kd2, waveform spec v5.2 (supersedes an earlier faint/flat-only
 * variant): the mic-loudness tick-bar directly under [BigTimer], visible in
 * every duck-view state -- the debug view's own [LoudnessMeterBar] already
 * gave this same "we're hearing you" signal; this restores it to the home
 * duck view too, in three renderings keyed off [activityState] alone (never
 * a separate "is recording" boolean a caller could let drift out of sync
 * with the duck's own pose):
 *
 * - [RecordingActivityState.SPEAKING]/[RecordingActivityState.QUIET]: red,
 *   live-moving ticks fed by [micLevel] -- audio is actively being saved.
 * - [RecordingActivityState.AUTO_PAUSED]: grey ticks that keep moving off
 *   the same live [micLevel] -- the mic is still open on its ring buffer
 *   (design board: "he can still hear you, but nothing is being saved"),
 *   just not persisted.
 * - [RecordingActivityState.USER_PAUSED]: the bar goes completely flat (a
 *   synthetic, non-live history -- the mic is released, there's nothing to
 *   show) and a studio-style unlit [NotRecordingSign] lights the same spot
 *   instead. That sign is a deliberate, one-off exception to this screen's
 *   locked no-corner-labels rule -- manual pause only; auto-pause still
 *   shows no sign at all, per [RecordingScreen]'s class KDoc.
 */
@Composable
private fun DuckWaveformBar(
    activityState: RecordingActivityState,
    micLevel: Float,
    sessionId: String?,
    modifier: Modifier = Modifier,
) {
    if (activityState == RecordingActivityState.USER_PAUSED) {
        Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DuckWaveformTicks(
                history = FLAT_WAVEFORM_HISTORY,
                tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().testTag(DUCK_WAVEFORM_FLAT_TEST_TAG),
            )
            Spacer(modifier = Modifier.height(6.dp))
            NotRecordingSign()
        }
        return
    }

    val visualizer = remember(sessionId) { LoudnessVisualizer() }
    var history by remember(visualizer) { mutableStateOf(visualizer.history) }
    LaunchedEffect(micLevel, visualizer) {
        visualizer.onLevel(micLevel)
        history = visualizer.history
    }
    val isAutoPaused = activityState == RecordingActivityState.AUTO_PAUSED
    val tickColor = if (isAutoPaused) {
        // Device-test directive (drop #1, item 4): brighter than a plain
        // dimmed onSurfaceVariant so "still hearing you" reads at a glance
        // rather than nearly disappearing against a dark background.
        AUTO_PAUSED_WAVEFORM_GREY
    } else {
        MaterialTheme.colorScheme.error
    }
    val testTag = if (isAutoPaused) DUCK_WAVEFORM_AUTO_PAUSED_TEST_TAG else DUCK_WAVEFORM_RECORDING_TEST_TAG
    DuckWaveformTicks(history = history, tickColor = tickColor, modifier = modifier.fillMaxWidth().testTag(testTag))
}

/**
 * Pure rendering half of [DuckWaveformBar] -- thin (unlike the debug view's
 * much taller [LoudnessMeterBarContent]) vertically-centered ticks in a
 * single [Canvas] pass, one row, full width, colored by the caller.
 */
@Composable
private fun DuckWaveformTicks(history: List<Float>, tickColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(DUCK_WAVEFORM_HEIGHT)) {
        val barCount = history.size
        if (barCount == 0) return@Canvas
        val gapPx = LOUDNESS_METER_BAR_GAP.toPx()
        val barWidth = ((size.width - gapPx * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val minTickPx = DUCK_WAVEFORM_MIN_TICK_HEIGHT.toPx().coerceAtMost(size.height)
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        history.forEachIndexed { index, level ->
            val tickHeight = (size.height * level.coerceIn(0f, 1f)).coerceAtLeast(minTickPx)
            val left = index * (barWidth + gapPx)
            drawRoundRect(
                color = tickColor,
                topLeft = Offset(left, (size.height - tickHeight) / 2f),
                size = Size(barWidth, tickHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/**
 * A constant, non-live history for [RecordingActivityState.USER_PAUSED]'s
 * "completely flat" waveform (design board v5.2) -- deliberately not driven
 * by [LoudnessVisualizer]/live mic level at all, since manual pause is a
 * hard mute (mic released, see [RecordingScreen]'s class KDoc): there is no
 * live level to show, so this renders a fixed, uniformly-low tick row
 * instead of letting the last-seen level linger on screen.
 */
private val FLAT_WAVEFORM_HISTORY = List(LoudnessVisualizer.DEFAULT_HISTORY_LENGTH) { FLAT_WAVEFORM_TICK_LEVEL }
private const val FLAT_WAVEFORM_TICK_LEVEL = 0.05f

/** Device-test directive (drop #1, item 4): a visible mid-grey, not a barely-there theme tint -- the AUTO_PAUSED waveform needs to read as "still moving" at a glance. */
private val AUTO_PAUSED_WAVEFORM_GREY = Color(0xFF9A9188)

// Device-test directive (drop #1, item 4): the waveform was the very first
// thing the live tester looked for -- taller and with a higher tick floor
// than the original spec's bare-minimum "thin" reading, so it registers
// immediately instead of blending into the background under the timer.
private val DUCK_WAVEFORM_HEIGHT = 22.dp
private val DUCK_WAVEFORM_MIN_TICK_HEIGHT = 3.dp

/** Test-only anchors for [DuckWaveformBar]'s three renderings (bead asn-kd2). */
const val DUCK_WAVEFORM_RECORDING_TEST_TAG = "recording_duck_waveform_recording"
const val DUCK_WAVEFORM_AUTO_PAUSED_TEST_TAG = "recording_duck_waveform_auto_paused"
const val DUCK_WAVEFORM_FLAT_TEST_TAG = "recording_duck_waveform_flat"

/**
 * The studio-style unlit "on-air lamp" sign (design board v5.2) that lights
 * the waveform's spot during [RecordingActivityState.USER_PAUSED] -- a
 * deliberate, manual-pause-only exception to this screen's locked
 * no-corner-labels rule (see [RecordingScreen]'s class KDoc): auto-pause
 * still shows no sign or label of any kind, only this hard-mute state does,
 * because "mic released" is the one thing here that isn't otherwise visible
 * anywhere else on screen (the sleeping duck and frozen timer look
 * IDENTICAL to auto-pause). Colors are hardcoded, not MaterialTheme-derived
 * -- same convention as this file's other locked-design elements (tag rail
 * chip colors, [StopBar]'s error red) -- since the whole point is an unlit,
 * dark studio placard rather than a theme-following surface.
 */
@Composable
private fun NotRecordingSign(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NOT_RECORDING_BG_COLOR)
            .border(1.5.dp, NOT_RECORDING_BORDER_COLOR, RoundedCornerShape(6.dp))
            .testTag(NOT_RECORDING_SIGN_TEST_TAG)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = "● NOT RECORDING",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontSize = 10.sp),
            fontWeight = FontWeight.Bold,
            color = NOT_RECORDING_TEXT_COLOR,
        )
    }
}

/** Test-only anchor for [NotRecordingSign] (bead asn-kd2). */
const val NOT_RECORDING_SIGN_TEST_TAG = "recording_not_recording_sign"

private val NOT_RECORDING_BG_COLOR = Color(0xFF211D18)
private val NOT_RECORDING_BORDER_COLOR = Color(0xFF4A4437)
private val NOT_RECORDING_TEXT_COLOR = Color(0xFF6E6353)

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
 * [RecordingScreen]'s own [REGISTER_KEY_MESSAGE_TEST_TAG] usage) and the
 * debug view's tags-slot keyless message ([TagRailSection]) so the reader
 * understands *why* nothing is transcribing, distinct from "recording, key
 * present, just no speech yet" (which still renders the normal
 * "Listening…"/"(silence)" rows below via the unchanged keyed path). Bead
 * asn-3sm: this whole pane now lives in the double-tap debug view rather
 * than being always-visible -- unchanged otherwise.
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

/** Test-only anchor for the keyless message shared by the duck view's word cloud (bead vn-edu.46, relocated by bead asn-3sm) and the debug view's [TagRailSection] -- the two are mutually exclusive, so one tag safely covers both. */
const val REGISTER_KEY_MESSAGE_TEST_TAG = "recording_register_key_message"

/**
 * Bead asn-45m: the editable tag rail (chips, each with a ✕ remove and a
 * trailing (+) add chip) plus the "Filing to: ..." ribbon underneath it,
 * pinned above the transcript pane -- supersedes the old read-only,
 * confidence-tiered TagChipsRow this replaced (see git history for
 * bead vn-edu.38/vn-edu.46/vn-edu.47's version of this slot): [rail] chips
 * are colored by [TagRailChip.status]/[TagRailChip.approved] (bead asn-0jk's
 * locked tag-colors design -- see [TagRailChipView]), not by
 * [TagRailChip.source] directly -- the bead's whole point is that this slot
 * is now something the user edits, not just a live readout.
 *
 * Bead vn-edu.46's superseding decision -- keyless shows the exact
 * register-key message instead of guessing tags, never a stray chip from a
 * misbehaving scorer -- still holds for [RailChipSource.SUGGESTED] chips
 * specifically: [visibleRail] drops them entirely while
 * [anthropicKeyConfigured] is false, same defensive guarantee as before this
 * bead (a keyless [com.montauk.voicecapture.tags.NoOpTagScorer] should never
 * suggest anything anyway, but this doesn't trust that alone). What's new is
 * that a [RailChipSource.USER] chip -- added manually via the (+) chip's
 * picker, which never touches the scorer -- always shows regardless of key
 * state, so the message now only appears when there's truly nothing to show:
 * no suggestions possible (keyless) *and* no user tag added yet either.
 */
@Composable
private fun TagRailSection(
    rail: List<TagRailChip>,
    anthropicKeyConfigured: Boolean,
    destination: String,
    onRegisterKeyTapped: () -> Unit = {},
    onRemove: (String) -> Unit = {},
    onRequestAdd: () -> Unit = {},
    onRequestSwap: (String) -> Unit = {},
    onApprove: (String) -> Unit = {},
) {
    val visibleRail = if (anthropicKeyConfigured) rail else rail.filter { it.source == RailChipSource.USER }
    if (!anthropicKeyConfigured && visibleRail.isEmpty()) {
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag(TAG_RAIL_TEST_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleRail.forEach { chip ->
                key(chip.tag) {
                    // Bead asn-0jk: a still-unapproved PROPOSED_NEW chip's
                    // body tap approves it in place (no picker); every other
                    // chip's body tap still opens the picker to swap it.
                    val onTapBody = if (chip.status == TagStatus.PROPOSED_NEW && !chip.approved) {
                        { onApprove(chip.tag) }
                    } else {
                        { onRequestSwap(chip.tag) }
                    }
                    TagRailChipView(chip = chip, onRemove = { onRemove(chip.tag) }, onTapBody = onTapBody)
                }
            }
            AddTagChip(onClick = onRequestAdd)
        }
        Spacer(modifier = Modifier.height(6.dp))
        FilingDestinationRibbon(destination = destination)
    }
}

/** Test-only anchors for the tag rail's parts (bead asn-45m/asn-0jk). */
const val TAG_RAIL_TEST_TAG = "recording_tag_rail"
/** A [TagStatus.EXISTING] chip -- blue, regardless of [RailChipSource]. */
const val TAG_RAIL_CHIP_EXISTING_TEST_TAG = "recording_tag_rail_chip_existing"
/** A [TagStatus.PROPOSED_NEW] chip the user has approved -- green. */
const val TAG_RAIL_CHIP_APPROVED_TEST_TAG = "recording_tag_rail_chip_approved"
/** A still-unapproved [TagStatus.PROPOSED_NEW] chip -- purple, dashed. */
const val TAG_RAIL_CHIP_PROPOSED_TEST_TAG = "recording_tag_rail_chip_proposed"
const val TAG_RAIL_ADD_CHIP_TEST_TAG = "recording_tag_rail_add_chip"
const val FILING_DESTINATION_RIBBON_TEST_TAG = "recording_filing_destination_ribbon"

/** Bead asn-0jk's locked tag-colors design: color is a pure function of [TagRailChip.status]/[TagRailChip.approved] -- see [TagChipRail]'s own KDoc on [TagRailChip] for the exact mapping and why [TagRailChip.source] is NOT the color axis. */
private enum class ChipColorState { EXISTING, PROPOSED_UNAPPROVED, PROPOSED_APPROVED }

private val TagRailChip.colorState: ChipColorState
    get() = when {
        status == TagStatus.EXISTING -> ChipColorState.EXISTING
        approved -> ChipColorState.PROPOSED_APPROVED
        else -> ChipColorState.PROPOSED_UNAPPROVED
    }

// Hardcoded, not MaterialTheme-derived -- same convention as the app's other
// status chips (Chips.kt's UploadStateChip/SessionStatusChip), since these
// are specific brand colors from the locked design doc, not theme accents
// that should drift with a future theme change.
private val EXISTING_COLOR = Color(0xFF4A90D9)
private val PROPOSED_APPROVED_COLOR = Color(0xFF5FBF6E)
private val PROPOSED_UNAPPROVED_COLOR = Color(0xFF9B6BDF)
private val PROPOSED_DASH_PATTERN = floatArrayOf(9f, 6f)
private const val PROPOSED_BORDER_WIDTH_DP = 1.5f

/**
 * One rendered rail chip. Bead asn-0jk's locked tag-colors design: color is
 * [ChipColorState] alone (blue/[TagStatus.EXISTING], purple-dashed/still-
 * unapproved [TagStatus.PROPOSED_NEW], green/approved [TagStatus.PROPOSED_NEW])
 * -- see [TagRailChip]'s own KDoc for why [RailChipSource] isn't the color
 * axis anymore. Tapping the tag text requests a swap or an approval
 * depending on which state the chip is in (decided by the caller,
 * [TagRailSection], via [onTapBody]); tapping the ✕ removes the chip
 * ([onRemove]).
 *
 * [onTapBody]'s `clickable` and [onRemove]'s `clickable` are **siblings**
 * inside a plain (non-clickable) outer [Row] -- deliberately NOT one nested
 * inside the other. Nesting two `clickable`s (the outer wrapping the whole
 * chip body, the inner just the ✕) looked equivalent on paper -- the ✕'s
 * bounds don't geometrically overlap the tag text's -- but empirically broke
 * the outer tap entirely: any tap inside a chip with a nested clickable
 * descendant got swallowed by the inner one's gesture arbitration regardless
 * of where it actually landed (confirmed via [RecordingScreenTagRailTest]
 * while chasing that exact failure). Two sibling `clickable`s under a
 * shared plain container is the same shape Material3's own chip components
 * (`InputChip`'s trailing icon, etc.) use for this identical "tap body vs.
 * tap the small trailing icon" pattern, and it doesn't have this problem.
 */
@Composable
private fun TagRailChipView(chip: TagRailChip, onRemove: () -> Unit, onTapBody: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    val colorState = chip.colorState
    val (accentColor, testTag) = when (colorState) {
        ChipColorState.EXISTING -> EXISTING_COLOR to TAG_RAIL_CHIP_EXISTING_TEST_TAG
        ChipColorState.PROPOSED_APPROVED -> PROPOSED_APPROVED_COLOR to TAG_RAIL_CHIP_APPROVED_TEST_TAG
        ChipColorState.PROPOSED_UNAPPROVED -> PROPOSED_UNAPPROVED_COLOR to TAG_RAIL_CHIP_PROPOSED_TEST_TAG
    }
    val backgroundColor = if (colorState == ChipColorState.PROPOSED_UNAPPROVED) Color.Transparent else accentColor.copy(alpha = 0.20f)
    val dashedBorder = colorState == ChipColorState.PROPOSED_UNAPPROVED
    val borderModifier = if (dashedBorder) {
        Modifier.drawWithContent {
            drawContent()
            drawRoundRect(
                color = accentColor,
                cornerRadius = CornerRadius(size.minDimension / 2f),
                style = Stroke(width = PROPOSED_BORDER_WIDTH_DP.dp.toPx(), pathEffect = PathEffect.dashPathEffect(PROPOSED_DASH_PATTERN)),
            )
        }
    } else {
        Modifier.border(1.dp, accentColor.copy(alpha = 0.7f), shape)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(backgroundColor, shape)
            .then(borderModifier)
            .testTag(testTag)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
    ) {
        Text(
            text = chip.tag,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (colorState == ChipColorState.EXISTING) FontWeight.Medium else FontWeight.Bold,
            color = accentColor,
            modifier = Modifier.clickable(onClick = onTapBody),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Remove ${chip.tag}" }
                .padding(4.dp),
        ) {
            Text(text = "✕", style = MaterialTheme.typography.labelLarge, color = accentColor)
        }
    }
}

/** The rail's trailing (+) chip -- opens the tag-tree picker in "add" mode. */
@Composable
private fun AddTagChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .testTag(TAG_RAIL_ADD_CHIP_TEST_TAG)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "+",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Filing to: <destination>" (bead asn-45m) -- [destination] is already
 * fully resolved by the caller ([TagFilingDestination.destinationFor]); this
 * composable only renders it.
 */
@Composable
private fun FilingDestinationRibbon(destination: String) {
    Text(
        text = "Filing to: $destination",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.fillMaxWidth().testTag(FILING_DESTINATION_RIBBON_TEST_TAG),
    )
}

/**
 * The STOP control, in two treatments (bead vn-edu.32 original + asn-3sm's
 * design-board addendum):
 *  - [floating] = false (debug view, no duck to float over): the original
 *    large full-bleed-minus-margins button, [STOP_BUTTON_HORIZONTAL_MARGIN]
 *    side margins + [navigationBarsPadding], at least [STOP_BUTTON_MIN_HEIGHT]
 *    tall.
 *  - [floating] = true (duck view): a small pill (matches the design
 *    board's `.btn-stop`), still >= 48dp touch target, with an explicit
 *    [FLOATING_BUTTON_ELEVATION] drop shadow so it reads as floating above
 *    the duck's yellow rather than blending into him. Deliberately sized to
 *    its own content -- not wrapped in a `fillMaxWidth()` self-centering
 *    box -- since its one caller (the duck view's `controls` row) places it
 *    at the row's own end via `Arrangement.SpaceBetween` (device-test
 *    directive, drop #1 item b: STOP stays pinned bottom-right while the
 *    pause/resume pill takes the opposite, bottom-left slot); a
 *    self-centering wrapper here would have consumed that row's entire
 *    remaining width and swallowed the `SpaceBetween` effect. [DuckStage]
 *    positions the whole row so it overlaps the duck's lower body/feet;
 *    this composable only owns the button's own look.
 */
@Composable
private fun StopBar(modifier: Modifier = Modifier, onClick: () -> Unit, floating: Boolean = false) {
    if (floating) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = FLOATING_BUTTON_MIN_HEIGHT).shadow(FLOATING_BUTTON_ELEVATION, RoundedCornerShape(999.dp)),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
        ) {
            Text(text = "STOP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
        }
        return
    }
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

/**
 * Bead asn-r60's state machine, asn-o63's bug fix, asn-3sm's presentation
 * (team-lead's v2 pause redesign supersedes asn-r60/asn-o63's own
 * `PauseBanner`/`BottomActionsBar` UI outright -- see [RecordingScreen]'s
 * `controls` slot): a small floating pill in the bottom-left slot (device-
 * test directive, drop #1 item b -- an IN-PLACE swap, not a separate pill
 * next to STOP), same visual language ([FLOATING_BUTTON_ELEVATION] shadow,
 * fully rounded, >= 48dp touch target) as the floating STOP pill.
 *
 * **Text and color depend on the pause KIND, not just paused-vs-not**
 * (device-test directive, drop #1 item c): the two pause kinds behave
 * differently under the hood (see [RecordingActivityState]'s own KDoc) and
 * the button now says so --
 *  - not paused (SPEAKING/QUIET): "⏸ PAUSE", neutral
 *    [MaterialTheme.colorScheme.secondaryContainer].
 *  - [RecordingActivityState.AUTO_PAUSED]: "▶ JUST SPEAK" -- mic still open
 *    on its ring buffer, sustained speech wakes the duck on its own; tapping
 *    this button also resumes, it's just not the only way.
 *  - [RecordingActivityState.USER_PAUSED]: "▶ RESUME" -- hard mute, mic
 *    released; this button is the ONLY way back.
 *
 * Both pause kinds render as a GREEN pill (matches the storyboard's
 * `.resume` pill) -- distinct from the neutral pause-state color -- since
 * either one is "tap this to make the duck listen again," just with
 * different urgency/mechanism behind it. Tapping while either kind of pause
 * is active always resumes (there is no other pause UI left to offer an
 * escalate-to-hard-pause affordance -- asn-o63 dropped the separate
 * auto-pause banner entirely, "no need for any other UI, keep it minimal",
 * which is exactly this bead's own v2 direction too).
 *
 * [fillFraction] (asn-o63's [com.montauk.voicecapture.service.TranscriptUiState.autoPauseFillFraction])
 * drives [AutoPauseFillOverlay]'s moving-gradient warning, shown only while
 * NOT already paused.
 */
@Composable
private fun PauseResumeChip(activityState: RecordingActivityState, fillFraction: Float, onSetPaused: (Boolean) -> Unit) {
    val isPaused = activityState == RecordingActivityState.USER_PAUSED || activityState == RecordingActivityState.AUTO_PAUSED
    val label = when (activityState) {
        RecordingActivityState.AUTO_PAUSED -> "▶ JUST SPEAK"
        RecordingActivityState.USER_PAUSED -> "▶ RESUME"
        RecordingActivityState.SPEAKING, RecordingActivityState.QUIET -> "⏸ PAUSE"
    }
    val containerColor = if (isPaused) RESUME_PILL_GREEN else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (isPaused) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Button(
        onClick = { onSetPaused(!isPaused) },
        modifier = Modifier
            .heightIn(min = FLOATING_BUTTON_MIN_HEIGHT)
            .shadow(FLOATING_BUTTON_ELEVATION, RoundedCornerShape(999.dp))
            .testTag(PAUSE_RESUME_BUTTON_TEST_TAG),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        // Deliberately keeps Button's default contentPadding rather than
        // zeroing it out (matches BottomActionsBar's own rationale before
        // this bead superseded it): the fill overlay is inset by that same
        // padding, matching the label's own inset, instead of running
        // edge-to-edge.
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!isPaused) {
                // asn-myi: matchParentSize(), not align(CenterStart) + the
                // overlay's own fillMaxSize() -- see AutoPauseFillOverlay's
                // KDoc for why the former alternative inflated this whole
                // button into a giant, screen-covering ellipse.
                AutoPauseFillOverlay(fillFraction = fillFraction, modifier = Modifier.matchParentSize())
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Bead v5.1 device-test directive: the resume-role pill's green, distinct from the neutral pause-state [MaterialTheme.colorScheme.secondaryContainer] -- matches the storyboard's `.resume` pill, hardcoded like this file's other locked-design colors (see [TagRailChipView]'s KDoc for the convention). */
private val RESUME_PILL_GREEN = Color(0xFF3FAE7A)

/**
 * Bead asn-o63: the Pause pill's interim "closing window" warning -- a
 * moving gradient sweep, clipped to [fillFraction] of the pill's width (0f
 * renders nothing, 1f fills the whole pill right as auto-pause fires).
 * [fillFraction] comes straight from [com.montauk.voicecapture.service.TranscriptUiState.autoPauseFillFraction] --
 * see that field's KDoc for the settings-aware math this doesn't need to
 * know about. Deliberately simple: a linear-gradient sweep, re-paced every
 * [AUTO_PAUSE_FILL_SHIMMER_PERIOD_MS] by a plain [delay] loop, is enough to
 * read as "something is progressing" without a bespoke shader.
 *
 * Ported from asn-o63's `BottomActionsBar` version onto this bead's
 * floating pill with one deliberate change: asn-o63's original paced the
 * sweep with `rememberInfiniteTransition`/`infiniteRepeatable`, which drives
 * Compose's own animation clock -- for a continuous animation expected to
 * run for the whole time this pill is paused-and-filling (not a brief,
 * finite transition), that clock never "finishes" the way
 * `ComposeTestRule.waitForIdle()` expects an animation to, and it left a
 * stuck idling resource that leaked into whichever test class happened to
 * run next in the same JVM fork (confirmed empirically: [RecordingScreenPauseTest]
 * alone was clean, but running it immediately before [RecordingScreenTagRailTest]
 * broke that class's clicks/double-taps). [DuckAnimator]'s own KDoc
 * documents this exact hazard and its fix -- pace off a plain [delay] loop,
 * same as here, not Compose's frame/animation clock.
 *
 * [modifier] MUST be `BoxScope.matchParentSize()`, not `fillMaxSize()`/
 * `align()` (asn-myi bug fix): this overlay sits inside
 * [PauseResumeChip]'s content `Box` alongside the "PAUSE"/"RESUME" [Text],
 * which is a content-sized (wrap-content) Button -- if this overlay's own
 * `BoxWithConstraints` asks for `fillMaxSize()`, it inflates the *incoming*
 * constraints that same Box (and the Button around it) resolves its own
 * size from, since [Box] sizes itself from the max of all non-
 * `matchParentSize` children including this one. A live tester saw exactly
 * that: the pause pill ballooned into a giant ellipse covering ~90% of the
 * screen the moment the fill fraction went above 0. `matchParentSize()` is
 * measured last, sized to whatever the Box already resolved from the [Text]
 * alone, so this overlay can never inflate its own container.
 */
@Composable
private fun AutoPauseFillOverlay(fillFraction: Float, modifier: Modifier = Modifier) {
    if (fillFraction <= 0f) return
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val startMs = System.currentTimeMillis()
        while (true) {
            val elapsedMs = System.currentTimeMillis() - startMs
            phase = (elapsedMs % AUTO_PAUSE_FILL_SHIMMER_PERIOD_MS).toFloat() / AUTO_PAUSE_FILL_SHIMMER_PERIOD_MS
            delay(AUTO_PAUSE_FILL_SHIMMER_TICK_MS)
        }
    }
    val colorA = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val colorB = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    // BoxWithConstraints (rather than Modifier.fillMaxWidth(fraction)) reads
    // this composable's own resolved incoming width explicitly, so the
    // fractional fill width is computed from a definite Dp regardless of how
    // Material3's Button content slot happens to propagate constraints to a
    // plain Box child. [modifier] is expected to already carry a fixed size
    // (matchParentSize() at the call site) -- this no longer chains its own
    // fillMaxSize(), which is exactly what let this overlay's requested size
    // leak upward into the button's own measurement (asn-myi).
    BoxWithConstraints(modifier = modifier) {
        val fillWidth = maxWidth * fillFraction.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(fillWidth)
                .testTag(AUTO_PAUSE_FILL_OVERLAY_TEST_TAG)
                .drawWithContent {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(colorA, colorB, colorA),
                            start = Offset(size.width * (phase - 1f), 0f),
                            end = Offset(size.width * phase, size.height),
                        ),
                    )
                },
        )
    }
}

/** Test-only anchor for [AutoPauseFillOverlay] (bead asn-o63). */
const val AUTO_PAUSE_FILL_OVERLAY_TEST_TAG = "recording_auto_pause_fill_overlay"
private const val AUTO_PAUSE_FILL_SHIMMER_PERIOD_MS = 1200L
private const val AUTO_PAUSE_FILL_SHIMMER_TICK_MS = 16L

/** Test-only anchor for [PauseResumeChip] (bead asn-r60/asn-o63's state, asn-3sm's presentation). */
const val PAUSE_RESUME_BUTTON_TEST_TAG = "recording_pause_resume_button"

private val FLOATING_BUTTON_MIN_HEIGHT = 48.dp
private val FLOATING_BUTTON_ELEVATION = 10.dp

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

/** Synthetic rail chips -- one suggested, one user-confirmed -- for the previews below (bead asn-45m). */
private fun syntheticRailChips(): List<TagRailChip> = listOf(
    TagRailChip("budget", tagId = null, source = RailChipSource.USER),
    TagRailChip("timeline", tagId = null, source = RailChipSource.SUGGESTED),
)

/**
 * Preview-only: the bottom of the debug view -- the tag rail + filing
 * ribbon, the air gap below them, and the (non-floating) STOP control --
 * rendered standalone at phone-bottom proportions (bead vn-edu.32) so the
 * inset-rounded-button treatment (margins, corner radius, clearance from the
 * gesture-nav inset) can be inspected without needing the full recording
 * state or a device. The duck view's own STOP + Pause/Resume treatment
 * (floating, over the duck) has its own preview -- see
 * `com.montauk.voicecapture.duck.DuckStagePreview`.
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
                TagRailSection(rail = syntheticRailChips(), anthropicKeyConfigured = true, destination = "notes/budget.md")
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
            Spacer(modifier = Modifier.height(16.dp))
            TagRailSection(rail = syntheticRailChips(), anthropicKeyConfigured = true, destination = "notes/budget.md")
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
            TagRailSection(rail = syntheticRailChips(), anthropicKeyConfigured = true, destination = "notes/budget.md")
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
