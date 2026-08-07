package com.montauk.voicecapture.duck

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure timing math for the vertical happy-bounce [DuckAnimator] layers over
 * the [DuckFrame.CELEBRATE] pose (bead asn-5w3 v5.2: "both wings up +
 * vertical happy bounce, ~600ms, 2 bounces") -- kept out of [DuckAnimator]
 * itself so the curve is unit-testable on the plain JVM without a Compose
 * test rule.
 *
 * [offsetFraction] returns a `0f..1f` "how far up" fraction for a given
 * elapsed time within the pulse: `0f` at the very start, the very end, and
 * the midpoint (the duck touches back down between the two bounces), with a
 * peak of `1f` at the apex of each bounce. [DuckAnimator] multiplies this by
 * a pixel amplitude and applies it as a negative `translationY`.
 * [reducedMotion] collapses the curve to a flat `0f` for every elapsed time
 * -- the celebrate pulse still plays (the pose swaps to [DuckFrame.CELEBRATE]
 * and back on the same schedule, see [DuckAnimationEngine]), it just holds
 * still instead of bouncing, matching how [ThoughtCloud]/[ZzTrail]'s own
 * `reducedMotion` handling falls back to a static render rather than
 * skipping the underlying state change.
 */
object DuckCelebrateBounce {
    /** Number of full up-down bounces across the pulse's duration. */
    const val BOUNCE_COUNT = 2

    fun offsetFraction(elapsedMs: Long, durationMs: Long, reducedMotion: Boolean = false): Float {
        if (reducedMotion) return 0f
        if (durationMs <= 0L || elapsedMs <= 0L || elapsedMs >= durationMs) return 0f
        val t = elapsedMs.toDouble() / durationMs.toDouble()
        return abs(sin(BOUNCE_COUNT * PI * t)).toFloat()
    }
}
