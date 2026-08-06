package com.montauk.voicecapture.duck

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * A continuously-repeating 0f..1f phase for the duck stage's decorative
 * looping animations ([ThoughtCloud]'s per-word drift/shimmer,
 * [ThoughtBubbleDots], [ZzTrail], and [com.montauk.voicecapture.ui.RecordingScreen]'s
 * auto-pause fill sweep) -- paced by a plain [delay] loop rather than
 * Compose's own animation clock (`rememberInfiniteTransition`/
 * `infiniteRepeatable`), same rationale [DuckAnimator]'s KDoc documents for
 * its own frame loop: a continuous animation with no natural "finish" keeps
 * Compose's animation clock alive for as long as the composable is on
 * screen, and `ComposeTestRule.waitForIdle()` either can't settle around it
 * or -- confirmed empirically chasing a flaky [RecordingScreenTagRailTest]
 * failure that only reproduced right after a duck-view render in the same
 * test JVM fork -- leaves a stuck idling resource behind that corrupts
 * whichever test class happens to run next.
 *
 * [repeatMode] mirrors `infiniteRepeatable`'s own parameter: [RepeatMode.Restart]
 * is a sawtooth (0->1, snap back to 0, repeat); [RepeatMode.Reverse] is a
 * triangle wave (0->1, then 1->0, repeat) -- the same "ping-pong" most of
 * this package's motion actually wants. [easing] applies within each leg
 * exactly like `tween(periodMs, easing)` would (a [RepeatMode.Reverse] leg
 * running backward applies the same curve to `1f - legFraction`, matching
 * how `infiniteRepeatable` replays a `tween` in reverse rather than mirroring
 * its already-eased output).
 *
 * [key] re-seeds the phase's start time (and restarts its [LaunchedEffect])
 * whenever it changes -- e.g. a per-word seed so multiple callers don't all
 * rise/fall in lockstep despite sharing a period.
 */
@Composable
fun rememberLoopingPhase(
    periodMs: Int,
    repeatMode: RepeatMode = RepeatMode.Restart,
    easing: Easing = LinearEasing,
    tickMs: Long = LOOPING_PHASE_TICK_MS,
    key: Any? = Unit,
): Float {
    var phase by remember(key) { mutableStateOf(0f) }
    LaunchedEffect(key) {
        val startMs = System.currentTimeMillis()
        while (true) {
            val elapsedMs = System.currentTimeMillis() - startMs
            val legFraction = (elapsedMs % periodMs).toFloat() / periodMs
            phase = when (repeatMode) {
                RepeatMode.Restart -> easing.transform(legFraction)
                RepeatMode.Reverse -> {
                    val legIndex = elapsedMs / periodMs
                    val forwardLeg = legIndex % 2 == 0L
                    if (forwardLeg) easing.transform(legFraction) else easing.transform(1f - legFraction)
                }
            }
            delay(tickMs)
        }
    }
    return phase
}

private const val LOOPING_PHASE_TICK_MS = 16L
