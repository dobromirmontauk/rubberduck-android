package com.montauk.voicecapture.duck

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.delay

/**
 * Frame-based clay-duck player (bead asn-3sm) over the pose library in
 * `design/clay-poses/` -- see [DuckAnimationEngine] for the pure
 * state->sequence logic this drives on a per-frame clock and renders.
 *
 * The continuous pose loop is paced with a plain [delay] rather than
 * Compose's own animation frame clock (`withFrameMillis`/`awaitFrame`)
 * deliberately: this composable runs for as long as the recording screen is
 * on screen, which -- unlike [Crossfade]'s brief, finite state transition
 * below -- never "finishes" the way `ComposeTestRule.waitForIdle()` expects
 * an animation to. Keeping frame pacing off Compose's `mainClock` means a
 * screenshot test can capture a deterministic single frame (see
 * [DuckPoseFrame], used directly by goldens) without the test's idle-wait
 * ever trying to fast-forward through this endless loop.
 *
 * `Crossfade` is keyed on [state]: every state renders a real
 * [DuckVisual.Pose] (an ordinary flipbook within one state -- no fade
 * between individual frames), and switching states crossfades between
 * whichever pose each was last showing. During that brief crossfade window
 * both the outgoing and incoming content read the same live [DuckVisual]
 * snapshot, so the outgoing copy may show a frame from the new state for
 * its last instant -- an accepted simplification for a ~260ms window, not a
 * visible glitch at normal viewing speed.
 */
@Composable
fun DuckAnimator(
    state: DuckState,
    modifier: Modifier = Modifier,
    happyBounceTrigger: Int = 0,
    handRaiseTrigger: Int = 0,
) {
    val engine = remember { DuckAnimationEngine(initialState = state) }
    var visual by remember { mutableStateOf(engine.tick(0L)) }

    LaunchedEffect(state) {
        engine.setState(state, nowMillis())
    }
    // Bead asn-0jk/asn-3sm: "Got it!" -- a new tag approved (or a summary
    // bullet added) plays a one-shot happy-bounce over whatever's currently
    // showing. [happyBounceTrigger]/[handRaiseTrigger] are plain incrementing
    // counters (not one-shot event flows) -- simplest reliable "fire on every
    // real change" signal for a Compose LaunchedEffect key; callers only ever
    // increment them, never reset to 0, so 0 itself never (re-)triggers either.
    LaunchedEffect(happyBounceTrigger) {
        if (happyBounceTrigger != 0) engine.triggerHappyBounce(nowMillis())
    }
    // Design board v2: a new tag entering the cloud plays a one-shot eager
    // hand-raise.
    LaunchedEffect(handRaiseTrigger) {
        if (handRaiseTrigger != 0) engine.triggerHandRaise(nowMillis())
    }
    LaunchedEffect(engine) {
        while (true) {
            visual = engine.tick(nowMillis())
            delay(FRAME_TICK_INTERVAL_MS)
        }
    }

    Box(modifier = modifier.testTag(DUCK_ANIMATOR_TEST_TAG), contentAlignment = Alignment.Center) {
        Crossfade(
            targetState = state,
            animationSpec = tween(DuckAnimationEngine.DEFAULT_CROSSFADE_MS.toInt()),
            label = "duck-state-crossfade",
        ) {
            DuckPoseFrame(visual = visual)
        }
    }
}

/**
 * Pure render of one [visual] frame -- no animation, no clock, no
 * coroutine. Used both by [DuckAnimator]'s per-frame content and directly by
 * screenshot goldens, which want one deterministic frame rather than
 * whatever the live loop above happens to land on.
 */
@Composable
internal fun DuckPoseFrame(visual: DuckVisual.Pose, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(visual.frame.drawableRes()),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
    )
}

private fun nowMillis(): Long = System.currentTimeMillis()

private const val FRAME_TICK_INTERVAL_MS = 16L

/** Test-only anchor for [DuckAnimator]. */
const val DUCK_ANIMATOR_TEST_TAG = "duck_animator"

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorListeningPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.LISTENING)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorSleepyPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.SLEEPY)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorSleepingPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.SLEEPING)
        }
    }
}
