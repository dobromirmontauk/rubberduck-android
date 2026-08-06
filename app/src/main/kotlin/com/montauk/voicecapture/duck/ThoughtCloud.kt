package com.montauk.voicecapture.duck

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * The duck's thought cloud (bead asn-3sm, design-board section 2/5 -- layout
 * A): up to [ThoughtCloudWords.MAX_TOP] EXISTING/PROPOSED/APPROVED words plus
 * up to [ThoughtCloudWords.MAX_CANDIDATES] muted CANDIDATE words, scattered
 * above the duck's head rather than in a tidy row. Supersedes the earlier
 * `WordCloud`/`TopicWordCloudTopics` (green/white only) with the full
 * asn-0jk color spec: BLUE existing, PURPLE proposed-new (tappable to
 * approve), GREEN approved, muted WHITE candidate. Word SIZE is confidence
 * (animated smoothly as confidence moves); word COLOR is status.
 *
 * Each word gets a small stable rotation (derived from its own text, so it
 * doesn't jitter frame to frame) plus, unless [reducedMotion], a gentle
 * drift+shimmer loop with a per-word period so the six words don't move in
 * lockstep. [onApprove] fires when a PROPOSED word is tapped -- callers
 * (see [com.montauk.voicecapture.ui.RecordingScreen]) are expected to record
 * the approval ([com.montauk.voicecapture.service.TagApprovalStateHolder])
 * and trigger the duck's happy-bounce; this composable only handles the
 * haptic tick and the tap gesture itself.
 */
@Composable
fun ThoughtCloud(
    words: List<ThoughtCloudWord>,
    reducedMotion: Boolean,
    onApprove: (ThoughtCloudWord) -> Unit,
    modifier: Modifier = Modifier,
    dimFactor: Float = 1f,
) {
    val view = LocalView.current
    BoxWithConstraints(modifier = modifier.testTag(THOUGHT_CLOUD_TEST_TAG).fillMaxSize()) {
        val stageHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
        val stageWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }

        // Confidence < ~0.3 fades a word out entirely rather than just
        // shrinking it further (design board v2: "shrinking below ~0.3
        // fades a word out of the cloud entirely; it can return") -- only
        // words that clear this bar are laid out at all, so a word that
        // fades out this round and comes back later re-enters cleanly.
        val visibleWords = words.filter { it.confidence >= CONFIDENCE_FADE_THRESHOLD || it.status != TagWordStatus.CANDIDATE }
        val topWords = visibleWords.filter { it.status != TagWordStatus.CANDIDATE }.take(ThoughtCloudWords.MAX_TOP)
        val candidateWords = visibleWords.filter { it.status == TagWordStatus.CANDIDATE }.take(ThoughtCloudWords.MAX_CANDIDATES)

        topWords.forEachIndexed { index, word ->
            val slot = TOP_SLOTS.getOrElse(index) { TOP_SLOTS.last() }
            ThoughtCloudWordView(
                word = word,
                slot = slot,
                reducedMotion = reducedMotion,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                dimFactor = dimFactor,
                onTap = {
                    if (word.status == TagWordStatus.PROPOSED) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onApprove(word)
                    }
                },
            )
        }
        candidateWords.forEachIndexed { index, word ->
            val slot = CANDIDATE_SLOTS.getOrElse(index) { CANDIDATE_SLOTS.last() }
            ThoughtCloudWordView(
                word = word,
                slot = slot,
                reducedMotion = reducedMotion,
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                dimFactor = dimFactor,
                onTap = {},
            )
        }
    }
}

/** Design board v2: "shrinking below ~0.3 fades a word out of the cloud entirely; it can return." */
private const val CONFIDENCE_FADE_THRESHOLD = 0.3

/** One word's fixed scatter position + size range -- see [TOP_SLOTS]/[CANDIDATE_SLOTS]. */
private data class CloudSlot(
    val topFraction: Float,
    val startFraction: Float?,
    val endFraction: Float?,
    val minFontSp: Float,
    val maxFontSp: Float,
)

// Mirrors the design board's layout-A phone mock scatter (top%, left%/right%)
// almost exactly -- see capture-design-board section 2's option A markup.
private val TOP_SLOTS = listOf(
    CloudSlot(topFraction = 0.02f, startFraction = 0.06f, endFraction = null, minFontSp = 15f, maxFontSp = 24f),
    CloudSlot(topFraction = 0.10f, startFraction = null, endFraction = 0.02f, minFontSp = 14f, maxFontSp = 22f),
    CloudSlot(topFraction = 0.20f, startFraction = 0.22f, endFraction = null, minFontSp = 13f, maxFontSp = 19f),
)
private val CANDIDATE_SLOTS = listOf(
    CloudSlot(topFraction = 0.30f, startFraction = 0.02f, endFraction = null, minFontSp = 11f, maxFontSp = 14f),
    CloudSlot(topFraction = 0.36f, startFraction = null, endFraction = 0.06f, minFontSp = 10f, maxFontSp = 13f),
    CloudSlot(topFraction = 0.45f, startFraction = 0.16f, endFraction = null, minFontSp = 10f, maxFontSp = 12f),
)

@Composable
private fun ThoughtCloudWordView(
    word: ThoughtCloudWord,
    slot: CloudSlot,
    reducedMotion: Boolean,
    stageWidthPx: Float,
    stageHeightPx: Float,
    dimFactor: Float,
    onTap: () -> Unit,
) {
    val color = when (word.status) {
        TagWordStatus.EXISTING -> TAG_COLOR_BLUE
        TagWordStatus.PROPOSED -> TAG_COLOR_PURPLE
        TagWordStatus.APPROVED -> TAG_COLOR_GREEN
        TagWordStatus.CANDIDATE -> TAG_COLOR_CANDIDATE
    }
    // Design board v2: "every word's size tracks its live confidence,
    // animating smoothly (300ms ease per rescore, ~5s cadence)".
    val fontSize by animateFloatAsState(
        targetValue = lerp(slot.minFontSp, slot.maxFontSp, word.confidence.toFloat().coerceIn(0f, 1f)),
        animationSpec = tween(CONFIDENCE_RESIZE_MS, easing = FastOutSlowInEasing),
        label = "thought-word-size-${word.text}",
    )
    val dimAlpha by animateFloatAsState(targetValue = dimFactor, label = "thought-word-dim-${word.text}")
    val baseRotation = stableRotationDegrees(word.text)
    val motion = rememberWordMotion(seed = word.text, reducedMotion = reducedMotion)

    val topPx = slot.topFraction * stageHeightPx
    val startPx = slot.startFraction?.let { it * stageWidthPx }
    val endPx = slot.endFraction?.let { it * stageWidthPx }

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = startPx ?: (stageWidthPx - (endPx ?: 0f) - size.width)
                translationY = topPx + motion.driftYPx
                rotationZ = baseRotation + motion.driftRotationDeg
                alpha = motion.shimmerAlpha * dimAlpha
            }
            .let { base -> if (word.status == TagWordStatus.PROPOSED) base.clickable(onClick = onTap) else base }
            .testTag(testTagFor(word.status) + word.text)
            .semantics {
                contentDescription = when (word.status) {
                    TagWordStatus.PROPOSED -> "${word.text}, proposed new tag, tap to approve"
                    TagWordStatus.APPROVED -> "${word.text}, approved new tag"
                    TagWordStatus.EXISTING -> word.text
                    TagWordStatus.CANDIDATE -> "${word.text}, candidate"
                }
            },
    ) {
        Text(
            text = if (word.status == TagWordStatus.PROPOSED) "${word.text} new?" else word.text,
            color = color,
            fontSize = fontSize.sp,
            fontWeight = if (word.status == TagWordStatus.CANDIDATE) FontWeight.Medium else FontWeight.Bold,
        )
    }
}

/** Live per-frame motion values for one word -- zeroed out entirely when [reducedMotion]. */
private class WordMotion(val driftYPx: Float, val driftRotationDeg: Float, val shimmerAlpha: Float)

@Composable
private fun rememberWordMotion(seed: String, reducedMotion: Boolean): WordMotion {
    if (reducedMotion) return WordMotion(0f, 0f, 1f)

    // Spreads the six words across three duration variants (5.2s/6.4s/7.1s
    // drift, 3.4s/4.1s/3.8s shimmer) rather than exactly replicating the
    // design board's CSS animation-delay (Compose's infiniteRepeatable has
    // no direct delay equivalent) -- the visual effect (words drifting out
    // of lockstep with each other) is the same.
    val variant = (seed.hashCode().mod(3))
    val driftPeriodMs = DRIFT_PERIOD_BASE_MS + variant * DRIFT_PERIOD_STEP_MS
    val shimmerPeriodMs = SHIMMER_PERIOD_BASE_MS + variant * SHIMMER_PERIOD_STEP_MS

    val driftT = rememberLoopingPhase(periodMs = driftPeriodMs, repeatMode = RepeatMode.Reverse, easing = FastOutSlowInEasing, key = "drift-$seed")
    val shimmerT = rememberLoopingPhase(periodMs = shimmerPeriodMs, repeatMode = RepeatMode.Reverse, key = "shimmer-$seed")
    val shimmerAlpha = 1f + (SHIMMER_MIN_ALPHA - 1f) * shimmerT
    return WordMotion(
        driftYPx = -driftT * DRIFT_TRANSLATE_Y_PX,
        driftRotationDeg = driftT * DRIFT_ROTATE_DEG,
        shimmerAlpha = shimmerAlpha,
    )
}

/** Stable, small (±10°) rotation derived from the word's own text -- never changes frame to frame. */
internal fun stableRotationDegrees(text: String): Float = (text.hashCode().mod(21) - 10).toFloat()

private fun testTagFor(status: TagWordStatus): String = when (status) {
    TagWordStatus.EXISTING -> EXISTING_WORD_TEST_TAG_PREFIX
    TagWordStatus.PROPOSED -> PROPOSED_WORD_TEST_TAG_PREFIX
    TagWordStatus.APPROVED -> APPROVED_WORD_TEST_TAG_PREFIX
    TagWordStatus.CANDIDATE -> CANDIDATE_WORD_TEST_TAG_PREFIX
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

/** Test-only anchors. Suffixed with the word's own text at the call site (see [testTagFor]) for per-word lookup. */
const val THOUGHT_CLOUD_TEST_TAG = "duck_thought_cloud"
const val EXISTING_WORD_TEST_TAG_PREFIX = "duck_word_existing_"
const val PROPOSED_WORD_TEST_TAG_PREFIX = "duck_word_proposed_"
const val APPROVED_WORD_TEST_TAG_PREFIX = "duck_word_approved_"
const val CANDIDATE_WORD_TEST_TAG_PREFIX = "duck_word_candidate_"

// asn-0jk's exact palette (design board :root custom properties).
val TAG_COLOR_BLUE = Color(0xFF5B8DEF)
val TAG_COLOR_PURPLE = Color(0xFFA275E3)
val TAG_COLOR_GREEN = Color(0xFF3FAE7A)
val TAG_COLOR_CANDIDATE = Color(0xFFB9AE9C)

private const val DRIFT_PERIOD_BASE_MS = 5200
private const val DRIFT_PERIOD_STEP_MS = 950
private const val SHIMMER_PERIOD_BASE_MS = 3400
private const val SHIMMER_PERIOD_STEP_MS = 350
private const val DRIFT_TRANSLATE_Y_PX = 18f
private const val DRIFT_ROTATE_DEG = 3f
private const val SHIMMER_MIN_ALPHA = 0.72f
private const val CONFIDENCE_RESIZE_MS = 300

@Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 300)
@Composable
private fun ThoughtCloudPreview() {
    VoiceCaptureTheme {
        ThoughtCloud(
            words = listOf(
                ThoughtCloudWord("family-trust", 0.9, TagWordStatus.EXISTING),
                ThoughtCloudWord("kitchen-remodel", 0.85, TagWordStatus.PROPOSED),
                ThoughtCloudWord("dog-walks", 0.8, TagWordStatus.APPROVED),
                ThoughtCloudWord("contractors", 0.4, TagWordStatus.CANDIDATE),
                ThoughtCloudWord("permits", 0.3, TagWordStatus.CANDIDATE),
            ),
            reducedMotion = false,
            onApprove = {},
        )
    }
}
