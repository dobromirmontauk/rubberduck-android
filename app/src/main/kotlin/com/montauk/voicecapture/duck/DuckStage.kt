package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.service.LatencyBadgeUiState
import com.montauk.voicecapture.service.SummaryUiState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * The duck view, "layout A" (bead asn-3sm, design board section 2 -- the
 * decided home state): the duck is bottom-anchored and owns at least
 * [DUCK_HEIGHT_FRACTION] of the stage's height; [ThoughtCloud] scatters its
 * words in the space above/around his head; [ThoughtBubbleDots] ties the two
 * together visually. [NotesCard] slides up over the lower third when
 * [summary] updates; [LatencyBadge] sits bottom-right (hidden at
 * [com.montauk.voicecapture.service.LatencySeverity.OK]). No persistent
 * notepad, no transcript, no other chrome --
 * [com.montauk.voicecapture.ui.RecordingScreen] layers the timer above and
 * the stop control below/over this stage.
 */
@Composable
fun DuckStage(
    duckState: DuckState,
    words: List<ThoughtCloudWord>,
    reducedMotion: Boolean,
    onApproveWord: (ThoughtCloudWord) -> Unit,
    summary: SummaryUiState,
    latencyState: LatencyBadgeUiState,
    onLatencyBadgeTap: () -> Unit,
    modifier: Modifier = Modifier,
    happyBounceTrigger: Int = 0,
) {
    Box(modifier = modifier.testTag(DUCK_STAGE_TEST_TAG).fillMaxSize()) {
        ThoughtCloud(
            words = words,
            reducedMotion = reducedMotion,
            onApprove = onApproveWord,
            modifier = Modifier.fillMaxSize(),
        )
        ThoughtBubbleDots(
            reducedMotion = reducedMotion,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        DuckAnimator(
            state = duckState,
            happyBounceTrigger = happyBounceTrigger,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(DUCK_HEIGHT_FRACTION),
        )
        LatencyBadge(
            state = latencyState,
            onTap = onLatencyBadgeTap,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
        NotesCard(summary = summary, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

const val DUCK_STAGE_TEST_TAG = "duck_stage"

/** Design-board sizing rule: "the duck owns at least half the screen height." */
const val DUCK_HEIGHT_FRACTION = 0.55f

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun DuckStageListeningPreview() {
    VoiceCaptureTheme {
        DuckStage(
            duckState = DuckState.LISTENING,
            words = listOf(
                ThoughtCloudWord("family-trust", 0.9, TagWordStatus.EXISTING),
                ThoughtCloudWord("kitchen-remodel", 0.85, TagWordStatus.PROPOSED),
                ThoughtCloudWord("dog-walks", 0.8, TagWordStatus.APPROVED),
                ThoughtCloudWord("contractors", 0.4, TagWordStatus.CANDIDATE),
                ThoughtCloudWord("permits", 0.3, TagWordStatus.CANDIDATE),
            ),
            reducedMotion = false,
            onApproveWord = {},
            summary = com.montauk.voicecapture.service.SummaryUiState(),
            latencyState = com.montauk.voicecapture.service.LatencyBadgeUiState(),
            onLatencyBadgeTap = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun DuckStageThinkingPreview() {
    VoiceCaptureTheme {
        DuckStage(
            duckState = DuckState.THINKING,
            words = listOf(ThoughtCloudWord("kitchen-remodel", 0.6, TagWordStatus.PROPOSED)),
            reducedMotion = false,
            onApproveWord = {},
            summary = com.montauk.voicecapture.service.SummaryUiState(),
            latencyState = com.montauk.voicecapture.service.LatencyBadgeUiState(),
            onLatencyBadgeTap = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun DuckStageSleepyPreview() {
    VoiceCaptureTheme {
        DuckStage(duckState = DuckState.SLEEPY, words = ThoughtCloudWords.EMPTY, reducedMotion = false, onApproveWord = {},
            summary = com.montauk.voicecapture.service.SummaryUiState(),
            latencyState = com.montauk.voicecapture.service.LatencyBadgeUiState(),
            onLatencyBadgeTap = {})
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun DuckStageSleepingPreview() {
    VoiceCaptureTheme {
        DuckStage(duckState = DuckState.SLEEPING, words = ThoughtCloudWords.EMPTY, reducedMotion = false, onApproveWord = {},
            summary = com.montauk.voicecapture.service.SummaryUiState(),
            latencyState = com.montauk.voicecapture.service.LatencyBadgeUiState(),
            onLatencyBadgeTap = {})
    }
}
