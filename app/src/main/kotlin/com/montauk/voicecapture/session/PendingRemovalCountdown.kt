package com.montauk.voicecapture.session

import kotlinx.coroutines.delay

/** Bead asn-638: how long a swiped row stays in its inline pending/Undo state before [PendingRemovalHolder.flush] commits it. */
const val PENDING_REMOVAL_WINDOW_MS = 10_000L

/** [PendingRemovalCountdown.run]'s progress-callback cadence -- purely cosmetic (how smoothly the countdown line shrinks), never affects when [PendingRemovalCountdown.run] calls `onElapsed`. */
private const val PENDING_REMOVAL_STEP_MS = 50L

/**
 * Bead asn-638: the actual "swipe -> pending -> commit" timer for one
 * pending row, split out of the `PendingSessionRow` composable that hosts it
 * -- mirroring [com.montauk.voicecapture.service.TooShortDecision] -- purely
 * so this timing logic is unit-testable with a virtual-time
 * `TestScope`/`runTest` (no Robolectric, no Compose frame clock) instead of
 * only exercisable by actually waiting out a real 10s window in a UI test.
 *
 * Deliberately built on plain [delay], not a Compose `Animatable`/`tween` --
 * a frame-clock-driven animation is exactly what a Compose UI test's
 * `mainClock` auto-advances to settle on every `waitForIdle()`/assertion,
 * which would fast-forward the *actual commit* the moment a test first
 * checks that the row is still pending. [PendingRemovalHolder]'s own
 * undo-window precedent (`AppNavHost`'s original snackbar timer) used the
 * same plain-coroutine approach for the identical reason.
 *
 * No separate cancel/stop method: the caller (a `LaunchedEffect` keyed on
 * the pending row's sessionId) owns the coroutine this runs in, so Undo (or
 * the row simply leaving composition) is ordinary structured-concurrency
 * cancellation -- [onElapsed] then never fires, and no partial commit can
 * leak out of a cancelled run.
 */
object PendingRemovalCountdown {
    suspend fun run(windowMs: Long = PENDING_REMOVAL_WINDOW_MS, onProgress: (Float) -> Unit, onElapsed: () -> Unit) {
        onProgress(1f)
        var elapsedMs = 0L
        while (elapsedMs < windowMs) {
            delay(PENDING_REMOVAL_STEP_MS)
            elapsedMs += PENDING_REMOVAL_STEP_MS
            onProgress((1f - elapsedMs.toFloat() / windowMs).coerceIn(0f, 1f))
        }
        onElapsed()
    }
}
