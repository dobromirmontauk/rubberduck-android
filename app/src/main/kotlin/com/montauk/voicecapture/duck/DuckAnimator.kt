package com.montauk.voicecapture.duck

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme
import kotlinx.coroutines.delay

/**
 * Single-frame clay-duck player (bead asn-5w3, superseding the earlier
 * continuous multi-frame-per-state loop -- see [DuckAnimationEngine]'s KDoc)
 * over the 8-pose library in `design/clay-poses/`: renders whatever
 * [DuckAnimationEngine.tick] resolves for [state]/[pulseTrigger], and
 * crossfades ([DuckAnimationEngine.DEFAULT_CROSSFADE_MS], ~220ms) whenever
 * that resolved [DuckFrame] changes -- a base-state change, a pulse
 * starting, or a pulse expiring back to base are all just "the frame
 * changed" to [Crossfade], keyed directly on [DuckVisual.Pose.frame]. While
 * the resolved frame is [DuckFrame.CELEBRATE], a separate bounded loop (see
 * below) additionally layers the vertical happy-bounce from
 * [DuckCelebrateBounce] on top of the crossfade.
 *
 * Bead asn-3gr: any transition touching [DuckFrame.BLINK] -- fading into it
 * or fading back out of it -- uses the faster
 * [DuckAnimationEngine.BLINK_CROSSFADE_MS] instead of the default, tracked
 * via [previousFrame] since a plain `visual.frame == BLINK` check alone only
 * catches the fade-in, not the fade-out (by the time the pulse expires and
 * the target flips back to the base state, `visual.frame` is no longer
 * `BLINK`). Combined with [DuckAnimationEngine.BLINK_PULSE_DURATION_MS], the
 * whole blink reads as one quick deliberate motion instead of a long static
 * dip -- the original bug this bead fixes.
 *
 * Deliberately **not** a continuous polling loop for state playback: the
 * old engine's frame cycling needed a per-frame `delay()` tick for the whole
 * lifetime of the recording screen, which is exactly the class of
 * long-lived background coroutine that left a stuck resource behind across
 * `ComposeTestRule` test boundaries. This engine only ever has *one* thing
 * to wait for -- the current pulse's own expiry -- so [LaunchedEffect] just
 * sleeps exactly [DuckAnimationEngine.msUntilNextTransition] and wakes up
 * once, re-keyed on [visual] so a new wait starts each time the resolved
 * frame actually changes; when nothing is scheduled (steady-state, no pulse
 * playing) there is no coroutine running at all. The celebrate-bounce loop
 * is the one exception, and it is deliberately *bounded*: it runs at most
 * [DuckAnimationEngine.CELEBRATE_PULSE_DURATION_MS] (~600ms, ~36 frames at
 * 60fps) before unconditionally exiting, paced by [withFrameMillis] (the
 * same composition frame clock Compose's own animation APIs use, so
 * `ComposeTestRule.waitForIdle()` settles around it correctly) rather than
 * a wall-clock `delay()` loop -- not the unbounded, whole-screen-lifetime
 * pattern the KDoc above warns about.
 */
@Composable
fun DuckAnimator(
    state: DuckState,
    modifier: Modifier = Modifier,
    pulseTrigger: DuckPulseEvent? = null,
    reducedMotion: Boolean = false,
) {
    val engine = remember { DuckAnimationEngine(initialState = state) }
    var visual by remember { mutableStateOf(engine.tick(nowMillis())) }
    var celebrateBounceFraction by remember { mutableStateOf(0f) }
    // Bead asn-3gr: remembers the previously-rendered frame so the
    // fade-*out* of BLINK (where `visual.frame` has already flipped back to
    // the base state) can still be detected as "touches BLINK" -- see class
    // KDoc. Updated via SideEffect, which runs after this composition, so
    // the check below always sees last frame's value, not this one's.
    val previousFrame = remember { mutableStateOf(visual.frame) }
    val crossfadeTouchesBlink = visual.frame == DuckFrame.BLINK || previousFrame.value == DuckFrame.BLINK
    SideEffect { previousFrame.value = visual.frame }

    LaunchedEffect(state) {
        engine.setState(state)
        visual = engine.tick(nowMillis())
    }
    // Bead asn-5w3: [pulseTrigger] carries its own nonce (see
    // [DuckPulseEvent]) so re-triggering the SAME pulse kind twice in a row
    // still fires -- a plain `DuckPulse` key wouldn't change if the same
    // pulse fires again before recomposition, and `LaunchedEffect` only
    // restarts when its key actually changes.
    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger != null) {
            engine.triggerPulse(pulseTrigger.pulse, nowMillis())
            visual = engine.tick(nowMillis())
        }
    }
    LaunchedEffect(visual) {
        val remainingMs = engine.msUntilNextTransition(nowMillis())
        if (remainingMs != null) {
            delay(remainingMs)
            visual = engine.tick(nowMillis())
        }
    }
    // Vertical happy-bounce while CELEBRATE is showing -- bounded to
    // CELEBRATE_PULSE_DURATION_MS, re-keyed on [visual] so it only runs
    // while the resolved frame is actually CELEBRATE (see class KDoc).
    LaunchedEffect(visual) {
        if (visual.frame == DuckFrame.CELEBRATE) {
            while (true) {
                val elapsedMs = engine.activePulseElapsedMs(nowMillis()) ?: break
                celebrateBounceFraction = DuckCelebrateBounce.offsetFraction(
                    elapsedMs = elapsedMs,
                    durationMs = DuckAnimationEngine.CELEBRATE_PULSE_DURATION_MS,
                    reducedMotion = reducedMotion,
                )
                if (elapsedMs >= DuckAnimationEngine.CELEBRATE_PULSE_DURATION_MS || reducedMotion) break
                withFrameMillis {}
            }
        }
        celebrateBounceFraction = 0f
    }

    Box(
        // Bead asn-dp2: contentDescription exposes which DuckState is
        // actually driving the animator right now -- a test hook only (no
        // visible/spoken effect for a sighted user; this stage isn't behind
        // a real accessibility tree anywhere else) so a Compose test can
        // assert the duck really switched to THINKING (the WRITE pose) for
        // exactly as long as the notes card is up, without needing to
        // inspect which drawable resource got rendered.
        modifier = modifier.testTag(DUCK_ANIMATOR_TEST_TAG).semantics { contentDescription = "duck_state_${state.name}" },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = visual.frame,
            animationSpec = tween(
                when {
                    reducedMotion -> 0
                    crossfadeTouchesBlink -> DuckAnimationEngine.BLINK_CROSSFADE_MS.toInt()
                    else -> DuckAnimationEngine.DEFAULT_CROSSFADE_MS.toInt()
                },
            ),
            label = "duck-frame-crossfade",
        ) { frame ->
            DuckPoseFrame(
                visual = DuckVisual.Pose(frame),
                modifier = Modifier.offset(y = CELEBRATE_BOUNCE_AMPLITUDE * -celebrateBounceFraction),
            )
        }
    }
}

/**
 * A single pulse request (bead asn-5w3): [nonce] is a plain incrementing
 * counter, so a caller that fires the *same* [pulse] kind twice in a row
 * (e.g. two "final transcription segment" NODs back to back) still produces
 * a new, distinct key for [LaunchedEffect] -- comparing `DuckPulse` alone
 * would silently coalesce those into one trigger.
 */
data class DuckPulseEvent(val pulse: DuckPulse, val nonce: Int)

/**
 * Pure render of one [visual] frame -- no animation, no clock, no
 * coroutine. Used both by [DuckAnimator]'s content and directly by
 * screenshot goldens, which want one deterministic frame rather than
 * whatever the live composable happens to land on.
 *
 * [Alignment.TopCenter] (bead asn-kd2), not [Image]'s own default
 * [Alignment.Center]: the pose assets are square (512x512) but
 * [DuckStage] hands this a tall, width-constrained box (its
 * `fillMaxHeight(DUCK_HEIGHT_FRACTION)` slice) -- `ContentScale.Fit` scales
 * by width and would otherwise center the duck vertically inside that box,
 * splitting the leftover space evenly above the head and below the feet.
 * Top-aligning instead collapses all of that slack to the bottom (below the
 * feet), so the top of the rendered sprite -- the head -- lands exactly at
 * the box's own top edge: the design board's "head sits at ~2/3 screen
 * height" (asn-bb4) and the fixed anchor [ZzTrail] now measures its
 * head-relative offsets from (previously this same gap silently pushed the
 * Z-trail's assumed head position well above where the head was actually
 * drawn -- see [DuckStage]'s KDoc).
 */
@Composable
internal fun DuckPoseFrame(visual: DuckVisual.Pose, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(visual.frame.drawableRes()),
        contentDescription = null,
        alignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize(),
    )
}

private fun nowMillis(): Long = System.currentTimeMillis()

/** Peak vertical travel of the [DuckFrame.CELEBRATE] happy-bounce (see [DuckCelebrateBounce]). */
private val CELEBRATE_BOUNCE_AMPLITUDE = 14.dp

/** Test-only anchor for [DuckAnimator]. */
const val DUCK_ANIMATOR_TEST_TAG = "duck_animator"

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorAttentivePreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.ATTENTIVE)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorDrowsyPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.DROWSY)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorSleepPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.SLEEP)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorThinkPreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.THINK)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorWritePreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.WRITE)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0E0E10, widthDp = 320, heightDp = 320)
@Composable
private fun DuckAnimatorCelebratePreview() {
    VoiceCaptureTheme {
        Box(modifier = Modifier.padding(24.dp).aspectRatio(1f)) {
            DuckAnimator(state = DuckState.ATTENTIVE, pulseTrigger = DuckPulseEvent(DuckPulse.CELEBRATE, nonce = 1))
        }
    }
}
