package com.montauk.voicecapture.duck

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.service.LatencyBadgeUiState
import com.montauk.voicecapture.service.SummaryUiState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * The duck view, "layout A" (bead asn-3sm, design board + v2 revision --
 * the decided home state): the duck is bottom-anchored and owns
 * [DUCK_HEIGHT_FRACTION] (~85%) of THIS composable's own box, head landing
 * near that box's own top edge; [ThoughtCloud] scatters its words in the
 * remaining space above/around his head; [ThoughtBubbleDots] ties the two
 * together visually. [ZzTrail] rises from his head during
 * [DuckState.DROWSY] (bead asn-3h6: "gets heavy-lidded with a rising trail
 * of Z's -- still recording", per the v5.3 storyboard) and
 * [DuckState.SLEEP]. [NotesCard] and [controls] both render as pure
 * overlays on top of the duck -- neither ever resizes or reflows him; see
 * each composable's own KDoc for why. [LatencyBadge] sits bottom-right
 * (hidden at [com.montauk.voicecapture.service.LatencySeverity.OK]). No
 * persistent notepad, no transcript, no other chrome.
 *
 * **This composable's own box must equal the screen's actual bottom
 * two-thirds (device-test/screen-gate fix, drop #1+#2).** The design
 * board's "duck fills the bottom two-thirds, head at ~2/3 screen height"
 * is ONE fraction, not two nested ones: [com.montauk.voicecapture.ui.RecordingScreen]
 * is responsible for handing this composable a `modifier` sized against
 * the TRUE full screen (`fillMaxHeight` measured at the root, not a
 * `Column`'s `weight(1f)` leftover space) -- see that file's KDoc for why
 * the earlier `Column`-based version silently broke this (a small, mostly-
 * empty top chrome plus an unrelated keyless-message line both ate into
 * the weighted box, so 67% of an already-85%-of-screen box put the duck's
 * head at ~41% down the screen instead of ~2/3 up from the bottom). Once
 * that outer box is correct, [DUCK_HEIGHT_FRACTION] fills nearly all of
 * it (not another 67%) -- the two fractions are NOT meant to compound.
 *
 * Bead asn-5w3 mechanical note: [happyBounceTrigger] (a tag was approved)
 * and the internal new-tag-entered-the-cloud detection below are each still
 * plain incrementing `Int` nonces from their original callers -- this
 * composable is what translates them into the pulse-based
 * [DuckAnimator]/[DuckAnimationEngine] API ([DuckPulse.CELEBRATE] and
 * [DuckPulse.RAISE_HAND] respectively). Real event-trigger wiring for the
 * other pulses ([DuckPulse.BLINK]/[DuckPulse.NOD]/[DuckPulse.WRITE]) is
 * asn-02h's job, not this bead's.
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
    controls: @Composable () -> Unit = {},
) {
    val dozingOrAsleep = duckState == DuckState.DROWSY || duckState == DuckState.SLEEP
    // Design board v2, frame 4: "duck switches to thinking/notes-scribble
    // pose; cloud words dim to 35%" while a summary round is in flight.
    val wordsDimFactor = if (duckState == DuckState.THINK) WORDS_DIM_FACTOR_WHILE_THINKING else 1f

    // Design board v2, frame 2: "a new tag enters the idea cloud and the
    // duck raises his hand eagerly" -- tracked here (not pushed onto
    // RecordingScreen) since DuckStage already owns comparing successive
    // [words] snapshots for everything else about the cloud. Only the
    // EXISTING/PROPOSED/APPROVED top set counts as "entering the cloud";
    // muted CANDIDATE words drifting in and out doesn't trigger this.
    var previousTopKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var handRaiseTrigger by remember { mutableStateOf(0) }
    val currentTopKeys = words.filter { it.status != TagWordStatus.CANDIDATE }.map { it.text.trim().lowercase() }.toSet()
    LaunchedEffect(currentTopKeys) {
        if (previousTopKeys.isNotEmpty() && (currentTopKeys - previousTopKeys).isNotEmpty()) {
            handRaiseTrigger++
        }
        previousTopKeys = currentTopKeys
    }

    // Folds the two legacy Int nonces above into a single DuckPulseEvent
    // stream for DuckAnimator -- see the class KDoc's asn-5w3 note.
    var pulseNonce by remember { mutableStateOf(0) }
    var pulseTrigger by remember { mutableStateOf<DuckPulseEvent?>(null) }
    LaunchedEffect(happyBounceTrigger) {
        if (happyBounceTrigger != 0) {
            pulseNonce++
            pulseTrigger = DuckPulseEvent(DuckPulse.CELEBRATE, pulseNonce)
        }
    }
    LaunchedEffect(handRaiseTrigger) {
        if (handRaiseTrigger != 0) {
            pulseNonce++
            pulseTrigger = DuckPulseEvent(DuckPulse.RAISE_HAND, pulseNonce)
        }
    }

    Box(modifier = modifier.testTag(DUCK_STAGE_TEST_TAG).fillMaxSize()) {
        ThoughtCloud(
            words = words,
            reducedMotion = reducedMotion,
            onApprove = onApproveWord,
            modifier = Modifier.fillMaxSize(),
            dimFactor = wordsDimFactor,
        )
        DuckAnimator(
            state = duckState,
            pulseTrigger = pulseTrigger,
            reducedMotion = reducedMotion,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(DUCK_HEIGHT_FRACTION),
        )
        if (dozingOrAsleep) {
            // Same bounds as the DuckAnimator box right above -- see
            // ZzTrail's own KDoc for why that shared box (plus
            // DuckPoseFrame's top-aligned image) makes this box's top edge
            // the duck's actual head, and TopCenter the trail's origin.
            ZzTrail(
                reducedMotion = reducedMotion,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(DUCK_HEIGHT_FRACTION),
            )
        } else {
            ThoughtBubbleDots(
                reducedMotion = reducedMotion,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxHeight(DUCK_HEIGHT_FRACTION),
            )
        }
        LatencyBadge(
            state = latencyState,
            onTap = onLatencyBadgeTap,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
        // Controls float ON the duck -- z-order above him, overlapping his
        // lower body (design-board addendum: "duck sits behind the control
        // row rather than above it"). A small negative bottom padding pulls
        // the row up off the true bottom edge and onto his feet without
        // covering his face, which sits well above DUCK_HEIGHT_FRACTION's
        // occluded slice.
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = CONTROLS_DUCK_OVERLAP)) {
            controls()
        }
        NotesCard(summary = summary, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

const val DUCK_STAGE_TEST_TAG = "duck_stage"

/**
 * Fraction of THIS composable's own box (which [com.montauk.voicecapture.ui.RecordingScreen]
 * must size to the screen's actual bottom two-thirds -- see this file's
 * class KDoc) the duck sprite fills, leaving a margin at the box's own top
 * for [ThoughtCloud] words to breathe above his head. Deliberately NOT
 * 0.67 -- that would re-apply the "two-thirds" idea a second time, nested
 * inside a box that's already exactly two-thirds of the screen.
 *
 * Bead asn-bb4.1, USER DECISION 2026-08-07 (supersedes an earlier 62-70%
 * target): the duck stays at max width with no side-cropping -- on a tall
 * phone that puts his head apex at ~50-55% of full SCREEN height, which is
 * the accepted spec (the storyboard's own 2/3 mock reflects a stubbier
 * mock aspect ratio, not a literal crop mandate). 0.78 of the outer box
 * (itself [com.montauk.voicecapture.ui.RecordingScreen]'s `DUCK_VIEW_SCREEN_FRACTION`,
 * 0.67 of the screen) lands the head apex at 0.67*0.78 ≈ 52.3% of screen
 * height -- see [com.montauk.voicecapture.ui.RecordingScreenDuckLayoutTest]
 * for the assertion against the real screen.
 */
const val DUCK_HEIGHT_FRACTION = 0.78f

/** How far up from the true bottom edge the control row sits, so it overlaps the duck's lower body/feet rather than floating below him. */
private val CONTROLS_DUCK_OVERLAP = 18.dp

private const val WORDS_DIM_FACTOR_WHILE_THINKING = 0.35f

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun DuckStageAttentivePreview() {
    VoiceCaptureTheme {
        DuckStage(
            duckState = DuckState.ATTENTIVE,
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
private fun DuckStageThinkPreview() {
    VoiceCaptureTheme {
        DuckStage(
            duckState = DuckState.THINK,
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
private fun DuckStageDrowsyPreview() {
    VoiceCaptureTheme {
        DuckStage(duckState = DuckState.DROWSY, words = ThoughtCloudWords.EMPTY, reducedMotion = false, onApproveWord = {},
            summary = com.montauk.voicecapture.service.SummaryUiState(),
            latencyState = com.montauk.voicecapture.service.LatencyBadgeUiState(),
            onLatencyBadgeTap = {})
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun DuckStageSleepPreview() {
    VoiceCaptureTheme {
        DuckStage(duckState = DuckState.SLEEP, words = ThoughtCloudWords.EMPTY, reducedMotion = false, onApproveWord = {},
            summary = com.montauk.voicecapture.service.SummaryUiState(),
            latencyState = com.montauk.voicecapture.service.LatencyBadgeUiState(),
            onLatencyBadgeTap = {})
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 640)
@Composable
private fun DuckStageCelebratePreview() {
    VoiceCaptureTheme {
        DuckStage(
            duckState = DuckState.ATTENTIVE,
            words = ThoughtCloudWords.EMPTY,
            reducedMotion = false,
            onApproveWord = {},
            summary = com.montauk.voicecapture.service.SummaryUiState(),
            latencyState = com.montauk.voicecapture.service.LatencyBadgeUiState(),
            onLatencyBadgeTap = {},
            happyBounceTrigger = 1,
        )
    }
}
