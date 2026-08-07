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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.delay

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
 *
 * **New-tag entrance (bead asn-bmq).** A top word this particular
 * [ThoughtCloud] instance has never shown before plays
 * [NewTagEntranceTimeline]'s WRITE -> MORPH -> DRIFT sequence (handwritten
 * letter reveal, crossfade to normal typography, then translate+scale into
 * its [rememberStableSlotAssignment]-assigned slot) before settling into
 * exactly today's steady-state rendering. "Never shown before" is tracked
 * per word key by [rememberFirstSeenAtMs], which deliberately treats the
 * very *first* composition's whole word set as an already-established
 * baseline (no entrance) -- only a word that shows up in some *later*
 * composition (the real "duck notices a new topic" case, e.g. a fresh
 * scorer suggestion arriving mid-session) counts as new. That baseline
 * carve-out is also what keeps a test/preview that seeds [words] with
 * several entries in one `setContent` call (e.g. [ThoughtCloudPreview],
 * `DuckScreenshotTest`) rendering exactly as before -- entrance timing only
 * ever engages for a word that *arrives after* this composable's first
 * frame. Candidate words don't participate -- the entrance is specifically
 * for a new top-set idea, not the muted also-rans.
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

        // Bead asn-76m: slots are assigned by each word's own stable key, NOT
        // by its position in `topWords`/`candidateWords` -- approving a word
        // promotes it in TagChipRail.chips()'s merge order (user chips move
        // to the front, ahead of remaining suggested chips), which reshuffles
        // every other word's *list index* even though nothing about their
        // own state changed. Indexing into TOP_SLOTS/CANDIDATE_SLOTS by that
        // list position (the old forEachIndexed) is exactly what made every
        // chip "jump" on a single approval. Keying by text instead means a
        // word keeps its slot for as long as it keeps appearing, independent
        // of where the merged list puts it.
        val topSlotForKey = rememberStableSlotAssignment(topWords.map { it.text }, TOP_SLOTS.size)
        val candidateSlotForKey = rememberStableSlotAssignment(candidateWords.map { it.text }, CANDIDATE_SLOTS.size)

        // Bead asn-bmq: which top words are "new" for entrance purposes --
        // see this composable's own KDoc for why the very first composition
        // is a no-entrance baseline.
        val topFirstSeenAtMs = rememberFirstSeenAtMs(topWords.map { it.text })

        topWords.forEach { word ->
            val slot = TOP_SLOTS[topSlotForKey.getValue(word.text)]
            val entrance = rememberEntranceSample(
                key = word.text,
                firstSeenAtMs = topFirstSeenAtMs.getValue(word.text),
                text = word.text,
                reducedMotion = reducedMotion,
            )
            if (entrance.phase == NewTagEntrancePhase.WRITE) {
                NewTagWriteView(
                    word = word,
                    entrance = entrance,
                    slot = slot,
                    stageWidthPx = stageWidthPx,
                    stageHeightPx = stageHeightPx,
                    dimFactor = dimFactor,
                )
            } else {
                ThoughtCloudWordView(
                    word = word,
                    slot = slot,
                    reducedMotion = reducedMotion,
                    stageWidthPx = stageWidthPx,
                    stageHeightPx = stageHeightPx,
                    dimFactor = dimFactor,
                    entrance = entrance,
                    onTap = {
                        if (word.status == TagWordStatus.PROPOSED) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onApprove(word)
                        }
                    },
                )
            }
        }
        candidateWords.forEach { word ->
            val slot = CANDIDATE_SLOTS[candidateSlotForKey.getValue(word.text)]
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

/**
 * Assigns each of [keys] a stable index into a fixed-size pool of
 * [slotCount] slots, remembered by key across recompositions (bead asn-76m
 * -- see the call site's KDoc for why index-based assignment made every
 * chip jump on a single approval). A key keeps whatever slot it was first
 * given for as long as it keeps appearing in [keys]; when it drops out (a
 * candidate's confidence faded below [CONFIDENCE_FADE_THRESHOLD], the
 * scorer stopped suggesting it) that slot frees up for the next key that
 * needs one. [keys] is expected never to exceed [slotCount] (callers pass
 * already-`take(MAX_TOP)`/`take(MAX_CANDIDATES)`-limited lists), so a free
 * slot always exists for a genuinely new key.
 *
 * Deliberately a plain (non-State) [remember]ed [MutableMap] mutated
 * in-place each composition, not a derived `remember(keys)` recompute --
 * the whole point is to *carry forward* the previous assignment across a
 * keys change rather than start over from it.
 */
@Composable
private fun rememberStableSlotAssignment(keys: List<String>, slotCount: Int): Map<String, Int> {
    val assignment = remember { mutableMapOf<String, Int>() }
    assignment.keys.retainAll(keys.toSet())
    val used = assignment.values.toHashSet()
    for (key in keys) {
        if (key !in assignment) {
            val freeSlot = (0 until slotCount).first { it !in used }
            assignment[key] = freeSlot
            used += freeSlot
        }
    }
    return assignment
}

/**
 * First-ever-seen wall-clock timestamp for each of [keys], remembered
 * across recompositions -- feeds [rememberEntranceSample]'s elapsed-time
 * calculation. The *very first* call (this composable's first frame) is
 * treated as an already-settled baseline: every key present then is
 * stamped with [BASELINE_FIRST_SEEN_AT_MS] (the Unix epoch), so
 * `now - firstSeenAtMs` is always far past [NewTagEntranceTimeline.TOTAL_MS]
 * and those words never animate in. Only a key that shows up in some
 * *later* call -- i.e. wasn't part of that first frame's set -- gets
 * stamped with the real current time and actually plays the entrance. Once
 * a key has a timestamp it keeps it forever (unlike [rememberStableSlotAssignment],
 * which frees a dropped key's slot for reuse) -- a word that leaves the top
 * set and later comes back must not replay its entrance a second time.
 */
@Composable
private fun rememberFirstSeenAtMs(keys: List<String>): Map<String, Long> {
    val firstSeen = remember { mutableMapOf<String, Long>() }
    val isFirstComposition = remember { BooleanHolder(true) }
    for (key in keys) {
        if (key !in firstSeen) {
            firstSeen[key] = if (isFirstComposition.value) BASELINE_FIRST_SEEN_AT_MS else System.currentTimeMillis()
        }
    }
    isFirstComposition.value = false
    return firstSeen
}

/** Plain (non-[androidx.compose.runtime.State]) remembered flag -- flipping it must NOT itself trigger recomposition, same rationale as [rememberStableSlotAssignment]'s plain [MutableMap]. */
private class BooleanHolder(var value: Boolean)

/** Sentinel "first seen" timestamp for a baseline (non-animating) word -- see [rememberFirstSeenAtMs]. */
private const val BASELINE_FIRST_SEEN_AT_MS = 0L

/** A word that has always been [NewTagEntrancePhase.SETTLED] -- the default for words that don't participate in the entrance (candidates) or haven't been wired up to a real [rememberFirstSeenAtMs] timestamp. */
private val ALWAYS_SETTLED = NewTagEntranceTimeline.Sample(NewTagEntrancePhase.SETTLED, 1f, 0)

/**
 * Samples [NewTagEntranceTimeline] once per frame for the word first seen
 * at [firstSeenAtMs], via a bounded [delay] loop -- same real-wall-clock
 * convention as [rememberLoopingPhase] (see its KDoc for why: Compose's own
 * animation clock is for continuous, never-finishing loops, not a one-shot
 * timeline like this). Unlike [rememberLoopingPhase] this loop is NOT
 * infinite: it stops ticking the moment the sampled phase reaches
 * [NewTagEntrancePhase.SETTLED], so a settled word (which is every word,
 * almost all of the time) leaves no live coroutine behind to complicate
 * `ComposeTestRule.waitForIdle()`.
 */
@Composable
private fun rememberEntranceSample(key: String, firstSeenAtMs: Long, text: String, reducedMotion: Boolean): NewTagEntranceTimeline.Sample {
    var sample by remember(key) {
        mutableStateOf(NewTagEntranceTimeline.at(System.currentTimeMillis() - firstSeenAtMs, text, reducedMotion))
    }
    LaunchedEffect(key, reducedMotion) {
        while (true) {
            val elapsedMs = System.currentTimeMillis() - firstSeenAtMs
            sample = NewTagEntranceTimeline.at(elapsedMs, text, reducedMotion)
            if (sample.phase == NewTagEntrancePhase.SETTLED) break
            delay(NEW_TAG_ENTRANCE_TICK_MS)
        }
    }
    return sample
}

private const val NEW_TAG_ENTRANCE_TICK_MS = 16L

/** How far above (px) and how much larger [NewTagEntrancePhase.WRITE]/[NewTagEntrancePhase.MORPH] render, before [NewTagEntrancePhase.DRIFT] eases both back down into the word's real [CloudSlot]. */
private const val ENTRY_RISE_PX = 70f
private const val ENTRY_SCALE = 1.3f

private fun colorFor(status: TagWordStatus): Color = when (status) {
    TagWordStatus.EXISTING -> TAG_COLOR_BLUE
    TagWordStatus.PROPOSED -> TAG_COLOR_PURPLE
    TagWordStatus.APPROVED -> TAG_COLOR_GREEN
    TagWordStatus.CANDIDATE -> TAG_COLOR_CANDIDATE
}

private fun displayTextFor(word: ThoughtCloudWord): String =
    if (word.status == TagWordStatus.PROPOSED) "${word.text} new?" else word.text

@Composable
private fun ThoughtCloudWordView(
    word: ThoughtCloudWord,
    slot: CloudSlot,
    reducedMotion: Boolean,
    stageWidthPx: Float,
    stageHeightPx: Float,
    dimFactor: Float,
    onTap: () -> Unit,
    entrance: NewTagEntranceTimeline.Sample = ALWAYS_SETTLED,
) {
    val color = colorFor(word.status)
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

    // Bead asn-bmq's DRIFT phase: eases the word from ENTRY_RISE_PX above
    // (and ENTRY_SCALE larger than) its real slot down into place. WRITE
    // renders through a separate composable ([NewTagWriteView]); MORPH holds
    // at the same entry offset/scale as WRITE while it crossfades typography
    // in place, then DRIFT is the only phase that actually moves anything.
    val (riseOffsetPx, entryScale) = when (entrance.phase) {
        NewTagEntrancePhase.WRITE, NewTagEntrancePhase.MORPH -> ENTRY_RISE_PX to ENTRY_SCALE
        NewTagEntrancePhase.DRIFT -> {
            val eased = FastOutSlowInEasing.transform(entrance.phaseProgress)
            lerp(ENTRY_RISE_PX, 0f, eased) to lerp(ENTRY_SCALE, 1f, eased)
        }
        NewTagEntrancePhase.SETTLED -> 0f to 1f
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = startPx ?: (stageWidthPx - (endPx ?: 0f) - size.width)
                translationY = topPx - riseOffsetPx + motion.driftYPx
                scaleX = entryScale
                scaleY = entryScale
                rotationZ = baseRotation + motion.driftRotationDeg
                alpha = motion.shimmerAlpha * dimAlpha
            }
            // Not clickable until fully SETTLED -- a word still WRITE-ing,
            // MORPH-ing, or DRIFT-ing shouldn't be approvable mid-flight.
            .let { base -> if (word.status == TagWordStatus.PROPOSED && entrance.phase == NewTagEntrancePhase.SETTLED) base.clickable(onClick = onTap) else base }
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
        if (entrance.phase == NewTagEntrancePhase.MORPH) {
            // Crossfade: the handwritten draft fades out as the normal
            // cloud typography (this word's real status color + badge)
            // fades in, both held at the WRITE phase's entry position/scale
            // (see riseOffsetPx/entryScale above) -- drifting only starts
            // once this crossfade is done.
            Text(
                text = word.text,
                color = color,
                fontSize = fontSize.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.Cursive,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { alpha = 1f - entrance.phaseProgress },
            )
            Text(
                text = displayTextFor(word),
                color = color,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { alpha = entrance.phaseProgress },
            )
        } else {
            Text(
                text = displayTextFor(word),
                color = color,
                fontSize = fontSize.sp,
                fontWeight = if (word.status == TagWordStatus.CANDIDATE) FontWeight.Medium else FontWeight.Bold,
            )
        }
    }
}

/**
 * Bead asn-bmq's WRITE phase: [word]'s text drawn in letter by letter, as
 * if the duck is writing it down, with a pencil trailing the reveal
 * (storyboard v5.2: "pencil at the edge") -- rendered above-and-larger-than
 * the word's real [CloudSlot] (matching [ThoughtCloudWordView]'s MORPH/DRIFT
 * entry offset, so the handoff into that composable at MORPH is seamless).
 * Not clickable and not tagged under [testTagFor] -- this is deliberately a
 * distinct, [NEW_TAG_WRITE_TEST_TAG_PREFIX]-tagged element, not yet the
 * word's real chip; [ThoughtCloudWordView] takes over (and the real,
 * status-based test tag starts existing) the moment the phase advances past
 * WRITE.
 */
@Composable
private fun NewTagWriteView(
    word: ThoughtCloudWord,
    entrance: NewTagEntranceTimeline.Sample,
    slot: CloudSlot,
    stageWidthPx: Float,
    stageHeightPx: Float,
    dimFactor: Float,
) {
    val color = colorFor(word.status)
    val dimAlpha by animateFloatAsState(targetValue = dimFactor, label = "thought-word-write-dim-${word.text}")
    val topPx = slot.topFraction * stageHeightPx
    val startPx = slot.startFraction?.let { it * stageWidthPx }
    val endPx = slot.endFraction?.let { it * stageWidthPx }
    val revealedText = word.text.take(entrance.lettersRevealed)

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = startPx ?: (stageWidthPx - (endPx ?: 0f) - size.width)
                translationY = topPx - ENTRY_RISE_PX
                scaleX = ENTRY_SCALE
                scaleY = ENTRY_SCALE
                alpha = dimAlpha
            }
            .testTag(NEW_TAG_WRITE_TEST_TAG_PREFIX + word.text)
            .semantics { contentDescription = "${word.text}, being written" },
    ) {
        Text(
            text = "$revealedText✏️", // trailing pencil -- storyboard v5.2: "pencil at the edge"
            color = color,
            fontSize = ((slot.minFontSp + slot.maxFontSp) / 2f).sp,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Cursive,
            fontWeight = FontWeight.Medium,
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

/** Bead asn-bmq: tags a word only while [NewTagEntrancePhase.WRITE] is drawing it in -- see [NewTagWriteView]. */
const val NEW_TAG_WRITE_TEST_TAG_PREFIX = "duck_word_entering_write_"

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
