package com.montauk.voicecapture.duck

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.montauk.voicecapture.tags.DisplayedTag
import com.montauk.voicecapture.tags.TagTier
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * What the duck's word cloud shows right now (bead asn-3sm): up to
 * [MAX_CONFIRMED] GREEN [confirmed] words (approved topics -- user-confirmed
 * or high-confidence) plus up to [MAX_CANDIDATES] smaller WHITE [candidates]
 * words (still under consideration).
 *
 * Deliberately just plain text lists, not tied to any one tag-tracking
 * implementation -- [fromDisplayedTags] adapts today's
 * [com.montauk.voicecapture.tags.TagTracker] /
 * [com.montauk.voicecapture.service.TagsStateHolder] output; agent asn-45m's
 * richer tag state machine (landing separately, with an explicit
 * user-confirmed flag) can supply this same shape via its own adapter
 * without [WordCloud] itself changing at all.
 */
data class TopicWordCloudTopics(
    val confirmed: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
) {
    companion object {
        const val MAX_CONFIRMED = 3
        const val MAX_CANDIDATES = 3

        val EMPTY = TopicWordCloudTopics()

        /**
         * Today's adapter: [DisplayedTag] has no explicit confirmed/candidate
         * flag yet, so [DisplayedTag.tier] stands in for it -- PRIMARY/
         * SECONDARY (already cleared [com.montauk.voicecapture.tags.TagTracker]'s
         * real entry bar for their rank slot) count as confirmed/GREEN;
         * TERTIARY or an explicit [DisplayedTag.isProposal] count as
         * candidate/WHITE. Revisit once asn-45m's user-confirmed state
         * lands -- that adapter should key directly off its own confirmed
         * flag instead of tier/isProposal.
         */
        fun fromDisplayedTags(tags: List<DisplayedTag>): TopicWordCloudTopics {
            val confirmed = tags.filter { !it.isProposal && it.tier != TagTier.TERTIARY }
                .map { it.tag }
                .take(MAX_CONFIRMED)
            val candidates = tags.filter { it.isProposal || it.tier == TagTier.TERTIARY }
                .map { it.tag }
                .take(MAX_CANDIDATES)
            return TopicWordCloudTopics(confirmed, candidates)
        }
    }
}

/**
 * Topic word cloud surrounding the duck (bead asn-3sm): [topics.confirmed]
 * render as up to 3 GREEN words, [topics.candidates] as 2-3 smaller WHITE
 * words, laid out at fixed positions around the duck rather than a real
 * physics-packed cloud -- deliberately simple for a glance-mode screen. Each
 * of the 6 slots is its own [AnimatedContent] keyed on slot index (same
 * fade+scale choreography [com.montauk.voicecapture.ui.RecordingScreen]'s
 * pre-asn-3sm `TagChipsRow` used) so a word entering/leaving reads as a
 * gentle fade rather than an abrupt layout jump.
 */
@Composable
fun WordCloud(topics: TopicWordCloudTopics, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(WORD_CLOUD_TEST_TAG).fillMaxSize()) {
        CONFIRMED_SLOT_ALIGNMENTS.forEachIndexed { slot, alignment ->
            key("confirmed-$slot") {
                WordSlot(
                    text = topics.confirmed.getOrNull(slot),
                    color = WORD_CLOUD_GREEN,
                    fontSize = CONFIRMED_FONT_SIZE,
                    testTag = "$CONFIRMED_WORD_TEST_TAG_PREFIX$slot",
                    modifier = Modifier.align(alignment).padding(WORD_CLOUD_EDGE_PADDING),
                )
            }
        }
        CANDIDATE_SLOT_ALIGNMENTS.forEachIndexed { slot, alignment ->
            key("candidate-$slot") {
                WordSlot(
                    text = topics.candidates.getOrNull(slot),
                    color = WORD_CLOUD_WHITE,
                    fontSize = CANDIDATE_FONT_SIZE,
                    testTag = "$CANDIDATE_WORD_TEST_TAG_PREFIX$slot",
                    modifier = Modifier.align(alignment).padding(WORD_CLOUD_EDGE_PADDING),
                )
            }
        }
    }
}

@Composable
private fun WordSlot(text: String?, color: Color, fontSize: TextUnit, testTag: String, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(WORD_ENTER_MS)) + scaleIn(initialScale = WORD_SCALE_FROM, animationSpec = tween(WORD_ENTER_MS)))
                .togetherWith(fadeOut(tween(WORD_EXIT_MS)) + scaleOut(targetScale = WORD_SCALE_FROM, animationSpec = tween(WORD_EXIT_MS)))
        },
        modifier = modifier,
        label = testTag,
    ) { t ->
        if (t != null) {
            Text(
                text = t,
                color = color,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(testTag),
            )
        }
    }
}

/** Test-only anchor for [WordCloud] itself. */
const val WORD_CLOUD_TEST_TAG = "duck_word_cloud"
const val CONFIRMED_WORD_TEST_TAG_PREFIX = "duck_word_confirmed_"
const val CANDIDATE_WORD_TEST_TAG_PREFIX = "duck_word_candidate_"

private val WORD_CLOUD_GREEN = Color(0xFF5FBF6E)
private val WORD_CLOUD_WHITE = Color(0xFFF2F2F2)
private val CONFIRMED_FONT_SIZE = 22.sp
private val CANDIDATE_FONT_SIZE = 15.sp
private val WORD_CLOUD_EDGE_PADDING = 12.dp
private const val WORD_ENTER_MS = 220
private const val WORD_EXIT_MS = 160
private const val WORD_SCALE_FROM = 0.85f

private val CONFIRMED_SLOT_ALIGNMENTS = listOf(Alignment.TopCenter, Alignment.CenterStart, Alignment.CenterEnd)
private val CANDIDATE_SLOT_ALIGNMENTS = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomCenter)

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 360)
@Composable
private fun WordCloudPreview() {
    VoiceCaptureTheme {
        WordCloud(
            topics = TopicWordCloudTopics(
                confirmed = listOf("kitchen remodel", "budget", "timeline"),
                candidates = listOf("vendor", "electrician"),
            ),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 360)
@Composable
private fun WordCloudEmptyPreview() {
    VoiceCaptureTheme {
        WordCloud(topics = TopicWordCloudTopics.EMPTY)
    }
}
