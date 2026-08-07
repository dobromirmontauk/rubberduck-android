package com.montauk.voicecapture.duck

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.montauk.voicecapture.service.SummaryUiState
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.delay

/**
 * The transient notes card (bead asn-3sm design-board sections 2 & 4, option
 * S1 "the duck's notes"; direction/position changed by bead asn-dp2's v2
 * revision -- storyboard v5.2 frames 5-7): enters moving LEFT-TO-RIGHT over
 * the word cloud (the stage's upper region -- [DuckStage] positions this
 * composable there; the duck himself is never covered) when a fresh
 * [SummaryUiState] lands, its newest bullet highlighted duck-yellow (the
 * glow fades over [NEWEST_GLOW_FADE_MS]), holds briefly, then continues out
 * to the right and fades -- [NotesCardChoreographer] owns exactly when.
 * Tapping the card pins it open.
 *
 * [onWritePoseActiveChanged] fires whenever
 * [NotesCardChoreographer.isWritePoseActive] changes, so a caller
 * ([DuckStage]/[com.montauk.voicecapture.ui.RecordingScreen]) can hold the
 * duck in his WRITE pose for exactly as long as the card is entering,
 * visible, or leaving (design board: "the duck keeps his write pose the
 * whole time the card is up").
 */
@Composable
fun NotesCard(
    summary: SummaryUiState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    onWritePoseActiveChanged: (Boolean) -> Unit = {},
) {
    val choreographer = remember { NotesCardChoreographer() }
    var mode by remember { mutableStateOf(choreographer.mode) }

    LaunchedEffect(summary.updatedAtMs) {
        if (summary.bullets.isNotEmpty()) {
            choreographer.onSummaryUpdated(nowMillis())
            mode = choreographer.mode
        }
    }
    LaunchedEffect(choreographer) {
        while (true) {
            val now = nowMillis()
            choreographer.tick(now)
            mode = choreographer.mode
            onWritePoseActiveChanged(choreographer.isWritePoseActive(now))
            delay(NOTES_CARD_TICK_INTERVAL_MS)
        }
    }

    Box(modifier = modifier.testTag(NOTES_CARD_CONTAINER_TEST_TAG), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = mode != NotesCardMode.HIDDEN,
            enter = if (reducedMotion) {
                slideInHorizontally(animationSpec = tween(0)) { 0 }
            } else {
                // Storyboard v5.2 frame 5: "card slides in from the LEFT edge,
                // moving left-to-right ... 300ms spring."
                slideInHorizontally(animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f)) { fullWidth -> -fullWidth }
            },
            exit = if (reducedMotion) {
                fadeOut(animationSpec = tween(0))
            } else {
                // Storyboard v5.2 frame 7: "continues out to the RIGHT ...
                // + fades (250ms ease-in)".
                slideOutHorizontally(animationSpec = tween(NotesCardChoreographer.EXIT_ANIMATION_MS.toInt())) { fullWidth -> fullWidth } +
                    fadeOut(animationSpec = tween(NotesCardChoreographer.EXIT_ANIMATION_MS.toInt()))
            },
        ) {
            NotesCardContent(
                summary = summary,
                reducedMotion = reducedMotion,
                onTap = {
                    choreographer.togglePin(nowMillis())
                    mode = choreographer.mode
                },
            )
        }
    }
}

@Composable
private fun NotesCardContent(summary: SummaryUiState, reducedMotion: Boolean, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NOTES_CARD_TEST_TAG)
            .background(NOTES_CARD_BACKGROUND, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .clickable(onClick = onTap)
            .padding(16.dp),
    ) {
        Text(
            text = "🗒 the duck's notes",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        summary.bullets.forEachIndexed { index, bullet ->
            if (index == summary.newestIndex) {
                NewestBullet(text = bullet, reducedMotion = reducedMotion)
            } else {
                Text(
                    text = "• $bullet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The newest bullet's own row: highlighted duck-yellow, fading to the
 * resting bullet color over [NEWEST_GLOW_FADE_MS] starting the instant it
 * appears (storyboard v5.2 frame 6: "New bullet highlighted duck-yellow,
 * glow fades over 800ms"). Keyed on [text] itself -- a genuinely new newest
 * bullet always has different text (see
 * [com.montauk.voicecapture.summary.AppendOnlyBulletMerge]), so this
 * naturally restarts the fade for each new highlight without needing a
 * separate "did the round change" signal threaded in from [NotesCard].
 * [glowTarget] starts each fresh [text] at [NOTES_CARD_NEWEST_COLOR] (so the
 * very first composed frame is already fully yellow, no animation on mount)
 * and a same-frame [LaunchedEffect] immediately requests the resting color,
 * which is what actually drives `animateColorAsState`'s tween -- animating
 * *from* yellow *to* resting starting right away, rather than
 * `animateColorAsState` only reacting to a target that changes sometime
 * later (which would either skip the glow entirely or need an extra
 * held-then-fade delay, neither of which matches "fades starting now").
 * [reducedMotion] skips the animation entirely -- the bullet still reads as
 * "new" (static duck-yellow, bold) without looping any transition.
 */
@Composable
private fun NewestBullet(text: String, reducedMotion: Boolean) {
    if (reducedMotion) {
        Text(
            text = "• $text",
            color = NOTES_CARD_NEWEST_COLOR,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp).testTag(NOTES_CARD_NEWEST_BULLET_TEST_TAG),
        )
        return
    }
    val restingColor = MaterialTheme.colorScheme.onSurfaceVariant
    var glowTarget by remember(text) { mutableStateOf(NOTES_CARD_NEWEST_COLOR) }
    LaunchedEffect(text, restingColor) {
        glowTarget = restingColor
    }
    val color by animateColorAsState(
        targetValue = glowTarget,
        animationSpec = tween(NEWEST_GLOW_FADE_MS.toInt()),
        label = "notes-card-newest-glow",
    )
    Text(
        text = "• $text",
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 4.dp).testTag(NOTES_CARD_NEWEST_BULLET_TEST_TAG),
    )
}

private fun nowMillis(): Long = System.currentTimeMillis()

/** Test-only anchors. */
const val NOTES_CARD_CONTAINER_TEST_TAG = "duck_notes_card_container"
const val NOTES_CARD_TEST_TAG = "duck_notes_card"
const val NOTES_CARD_NEWEST_BULLET_TEST_TAG = "duck_notes_card_newest_bullet"

private val NOTES_CARD_BACKGROUND = Color(0xFF211D18)
private val NOTES_CARD_NEWEST_COLOR = Color(0xFFF2C84B) // duck-yellow, matches design board --duck
private const val NOTES_CARD_TICK_INTERVAL_MS = 200L

/** Storyboard v5.2 frame 6: "New bullet highlighted duck-yellow (glow fades over 800ms)." */
private const val NEWEST_GLOW_FADE_MS = 800L

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 360, heightDp = 300)
@Composable
private fun NotesCardPreview() {
    VoiceCaptureTheme {
        NotesCard(
            summary = SummaryUiState(
                bullets = listOf(
                    "Comparing 3 contractor bids for kitchen remodel",
                    "Second bid \$72k includes permit filing",
                    "Budget cap set at \$80k -- need decision by Friday",
                ),
                newestIndex = 2,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }
}
