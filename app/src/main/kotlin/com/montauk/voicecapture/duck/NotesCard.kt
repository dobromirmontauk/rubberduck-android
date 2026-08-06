package com.montauk.voicecapture.duck

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * The transient notes card (bead asn-3sm, design-board sections 2 & 4,
 * option S1 "the duck's notes"): slides up over the stage's lower third
 * when a fresh [SummaryUiState] lands, its newest bullet highlighted
 * duck-yellow, holds briefly, then slides away -- [NotesCardChoreographer]
 * owns exactly when. Tapping the card pins it open; the slim peek handle at
 * the very bottom (design board's "swipe up anytime to peek") is always
 * present and, when tapped, briefly reveals the card the same way a fresh
 * bullet would -- a tap-to-peek stand-in for a real swipe-up drag gesture,
 * which is a reasonable simplification for how rarely this needs invoking
 * (the card already auto-shows on every new bullet).
 */
@Composable
fun NotesCard(summary: SummaryUiState, modifier: Modifier = Modifier) {
    val choreographer = remember { NotesCardChoreographer() }
    var mode by remember { mutableStateOf(choreographer.mode) }

    LaunchedEffect(summary.updatedAtMs) {
        if (summary.bullets.isNotEmpty()) {
            choreographer.onSummaryUpdated(System.currentTimeMillis())
            mode = choreographer.mode
        }
    }
    LaunchedEffect(choreographer) {
        while (true) {
            choreographer.tick(System.currentTimeMillis())
            mode = choreographer.mode
            delay(NOTES_CARD_TICK_INTERVAL_MS)
        }
    }

    Box(modifier = modifier.testTag(NOTES_CARD_CONTAINER_TEST_TAG), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = mode != NotesCardMode.HIDDEN,
            enter = slideInVertically(animationSpec = tween(NOTES_CARD_SLIDE_MS)) { it },
            exit = slideOutVertically(animationSpec = tween(NOTES_CARD_SLIDE_MS)) { it },
        ) {
            NotesCardContent(
                summary = summary,
                onTap = {
                    choreographer.togglePin(System.currentTimeMillis())
                    mode = choreographer.mode
                },
            )
        }
        if (mode == NotesCardMode.HIDDEN) {
            PeekHandle(
                onTap = {
                    choreographer.peek(System.currentTimeMillis())
                    mode = choreographer.mode
                },
            )
        }
    }
}

@Composable
private fun NotesCardContent(summary: SummaryUiState, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NOTES_CARD_TEST_TAG)
            .background(NOTES_CARD_BACKGROUND, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
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
            Text(
                text = "• $bullet",
                color = if (index == summary.newestIndex) NOTES_CARD_NEWEST_COLOR else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (index == summary.newestIndex) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PeekHandle(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .testTag(NOTES_CARD_PEEK_HANDLE_TEST_TAG)
            .padding(bottom = 6.dp)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(4.dp)
                .background(Color(0xFF2E2822), RoundedCornerShape(2.dp)),
        )
    }
}

/** Test-only anchors. */
const val NOTES_CARD_CONTAINER_TEST_TAG = "duck_notes_card_container"
const val NOTES_CARD_TEST_TAG = "duck_notes_card"
const val NOTES_CARD_PEEK_HANDLE_TEST_TAG = "duck_notes_card_peek_handle"

private val NOTES_CARD_BACKGROUND = Color(0xFF211D18)
private val NOTES_CARD_NEWEST_COLOR = Color(0xFFF2C84B) // duck-yellow, matches design board --duck
private const val NOTES_CARD_SLIDE_MS = 260
private const val NOTES_CARD_TICK_INTERVAL_MS = 200L

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
