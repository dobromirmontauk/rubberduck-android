package com.montauk.voicecapture.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.montauk.voicecapture.audio.LoudnessVisualizer
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.service.RecordingStateHolder
import com.montauk.voicecapture.service.TranscriptLine
import com.montauk.voicecapture.service.TranscriptStateHolder
import com.montauk.voicecapture.service.TranscriptUiState
import com.montauk.voicecapture.topics.TopicCloud
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The full-glance recording screen: huge timer, LIVE/OFFLINE + Bluetooth
 * chips, a mic-level bar, the mode switcher, the last couple of transcript
 * lines, topic chips, and a large inset STOP button anchored to the bottom
 * (bead vn-edu.32 -- a floating button with clear margins, not a full-bleed
 * slab flush with the screen edge, which read as sitting exactly where the
 * app's own bottom nav normally lives). No bottom nav here -- this screen is
 * meant to be readable at arm's length while walking.
 */
@Composable
fun RecordingScreen(onStopRecording: () -> Unit, onSetMode: (RecordingMode) -> Unit = {}) {
    val context = LocalContext.current
    val recordingState by RecordingStateHolder.state.collectAsStateWithLifecycle()
    val transcript by TranscriptStateHolder.state.collectAsStateWithLifecycle()
    val hasBluetoothMic = remember { hasBluetoothInputDevice(context) }
    val topics = remember(transcript.finalLines, recordingState.elapsedMs) {
        TopicCloud.compute(transcript.finalLines.map { TopicCloud.Line(it.text, it.endMs) }, nowMs = recordingState.elapsedMs)
    }

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
                    ChipsRow(transcript = transcript, hasBluetoothMic = hasBluetoothMic)
                    Spacer(modifier = Modifier.height(10.dp))
                    LoudnessMeterBar(level = transcript.micLevel, sessionId = recordingState.sessionId)
                    Spacer(modifier = Modifier.height(16.dp))
                    ModeSwitcher(currentMode = recordingState.mode, onSelect = onSetMode)
                    Spacer(modifier = Modifier.height(16.dp))
                    LiveTranscriptPane(transcript = transcript, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.height(16.dp))
                    TopicChipsRow(topics = topics)
                    // Extra air below the chips row (bead vn-edu.32) so the inset STOP
                    // button reads as floating above content, not touching the chips.
                    Spacer(modifier = Modifier.height(24.dp))
                }
                StopBar(modifier = Modifier.weight(1f), onClick = onStopRecording)
            }
        }
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

@Composable
private fun ChipsRow(transcript: TranscriptUiState, hasBluetoothMic: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SttStatusChip(transcript.connectionState)
        val (label, color) = when {
            // Debug-only audio injection (bead vn-edu.20) -- takes priority over
            // the live bluetooth/phone-mic detection below since there's no real
            // AudioRecord device to ask about while a file is standing in for one.
            transcript.sourceLabel == "FILE" -> "FILE" to Color(0xFFE0A93A)
            hasBluetoothMic -> "BLUETOOTH" to Color(0xFF5FBF6E)
            else -> "PHONE MIC" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        StatusChip(label, color)
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
 * Last ~2 finalized lines (large, solid) plus the current in-progress
 * partial (large, dimmed) underneath -- or, if neither has arrived in the
 * last ~4s and the mic is quiet, a dimmed italic "(silence)" line instead.
 */
@Composable
private fun LiveTranscriptPane(transcript: TranscriptUiState, modifier: Modifier = Modifier) {
    val lastFinal = transcript.finalLines.takeLast(2)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
        if (lastFinal.isEmpty() && transcript.currentPartial.isBlank() && !transcript.silenceHintVisible) {
            Text(
                text = "Listening…",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        lastFinal.forEach { line: TranscriptLine ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        when {
            transcript.currentPartial.isNotBlank() -> Text(
                text = transcript.currentPartial,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            transcript.silenceHintVisible -> Text(
                text = "(silence)",
                style = MaterialTheme.typography.headlineMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/** Topic chips sized by weight, largest first -- see [TopicCloud]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopicChipsRow(topics: List<TopicCloud.Topic>) {
    if (topics.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        topics.forEach { topic -> TopicChip(topic) }
    }
}

@Composable
private fun TopicChip(topic: TopicCloud.Topic) {
    val (fontSize, verticalPadding) = when (topic.tier) {
        TopicCloud.Tier.LARGE -> 18.sp to 10.dp
        TopicCloud.Tier.MEDIUM -> 15.sp to 8.dp
        TopicCloud.Tier.SMALL -> 13.sp to 6.dp
    }
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = verticalPadding),
    ) {
        Text(
            text = topic.word,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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

/** Synthetic topic chips, largest-weight first, for the bottom-of-screen preview below. */
private fun syntheticTopics(): List<TopicCloud.Topic> = listOf(
    TopicCloud.Topic("budget", 4.0, TopicCloud.Tier.LARGE),
    TopicCloud.Topic("timeline", 3.0, TopicCloud.Tier.MEDIUM),
    TopicCloud.Topic("vendor", 2.0, TopicCloud.Tier.MEDIUM),
    TopicCloud.Topic("permit", 1.0, TopicCloud.Tier.SMALL),
)

/**
 * Preview-only: the bottom of [RecordingScreen] -- topic chips, the air gap
 * below them, and the STOP control -- rendered standalone at phone-bottom
 * proportions (bead vn-edu.32) so the inset-rounded-button treatment (margins,
 * corner radius, clearance from the gesture-nav inset) can be inspected
 * without needing the full recording state or a device.
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
                TopicChipsRow(topics = syntheticTopics())
                Spacer(modifier = Modifier.height(24.dp))
            }
            StopBar(modifier = Modifier.weight(1f), onClick = {})
        }
    }
}
