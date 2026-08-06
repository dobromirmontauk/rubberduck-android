package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * The duck view (bead asn-3sm): [DuckAnimator] centered with [WordCloud]'s
 * green/white topic words layered around it. This is what
 * [com.montauk.voicecapture.ui.RecordingScreen] shows by default now that
 * the transcript is demoted to a debug-only view (double-tap toggle).
 */
@Composable
fun DuckStage(duckState: DuckState, topics: TopicWordCloudTopics, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(DUCK_STAGE_TEST_TAG).fillMaxSize(), contentAlignment = Alignment.Center) {
        WordCloud(topics = topics, modifier = Modifier.fillMaxSize())
        DuckAnimator(state = duckState, modifier = Modifier.fillMaxWidth(DUCK_SIZE_FRACTION).aspectRatio(1f))
    }
}

const val DUCK_STAGE_TEST_TAG = "duck_stage"
private const val DUCK_SIZE_FRACTION = 0.55f

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 480)
@Composable
private fun DuckStageListeningPreview() {
    VoiceCaptureTheme {
        DuckStage(
            duckState = DuckState.LISTENING,
            topics = TopicWordCloudTopics(
                confirmed = listOf("kitchen remodel", "budget", "timeline"),
                candidates = listOf("vendor", "electrician"),
            ),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 480)
@Composable
private fun DuckStageSleepyPreview() {
    VoiceCaptureTheme {
        DuckStage(duckState = DuckState.SLEEPY, topics = TopicWordCloudTopics.EMPTY)
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 480)
@Composable
private fun DuckStageBrbPreview() {
    VoiceCaptureTheme {
        DuckStage(duckState = DuckState.GONE_BRB, topics = TopicWordCloudTopics.EMPTY)
    }
}
