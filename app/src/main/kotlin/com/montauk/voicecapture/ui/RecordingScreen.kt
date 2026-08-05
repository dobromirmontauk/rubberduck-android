package com.montauk.voicecapture.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * lines, topic chips, and a full-width STOP bar owning the bottom fifth. No
 * bottom nav here -- this screen is meant to be readable at arm's length
 * while walking.
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
                    MicLevelBar(level = transcript.micLevel)
                    Spacer(modifier = Modifier.height(16.dp))
                    ModeSwitcher(currentMode = recordingState.mode, onSelect = onSetMode)
                    Spacer(modifier = Modifier.height(16.dp))
                    LiveTranscriptPane(transcript = transcript, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.height(16.dp))
                    TopicChipsRow(topics = topics)
                    Spacer(modifier = Modifier.height(16.dp))
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
        val (label, color) = if (hasBluetoothMic) {
            "BLUETOOTH" to Color(0xFF5FBF6E)
        } else {
            "PHONE MIC" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        StatusChip(label, color)
    }
}

/** Thin RMS bar under the chips -- instant visual proof the mic is hearing something. */
@Composable
private fun MicLevelBar(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

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

/** Full-width bottom bar owning the bottom fifth of the screen. */
@Composable
private fun StopBar(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Text(
            text = "STOP",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}
