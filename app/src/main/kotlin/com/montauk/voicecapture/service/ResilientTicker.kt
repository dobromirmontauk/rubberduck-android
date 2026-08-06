package com.montauk.voicecapture.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Bead asn-l34: runs [onTick] once every [intervalMs] for as long as [scope]
 * is active and [continueCondition] holds, isolating each call so a single
 * bad tick can never end the loop -- an exception thrown out of [onTick] is
 * caught, reported to [onTickFailure], and the loop moves on to the next
 * [delay]/tick exactly as if that tick had succeeded.
 *
 * [RecordingService.startTicker] is this loop's only production caller: its
 * per-tick work (publish [RecordingUiState.elapsedMs], repost the foreground
 * notification, feed the tag pump) used to run unguarded directly in the
 * `while` loop, so *any* one of those throwing -- e.g. `NotificationManager
 * .notify()` after a mid-session permission change, or any future addition
 * to the tick body -- silently and permanently froze the elapsed-time clock
 * for the rest of the recording, with no log line distinguishing that from
 * a normal stop. [RecordingService]'s own tag-pump loop already isolates
 * itself the same way, per-event, via `runCatching` (see
 * [RecordingService.startTagPipeline]); this gives the ticker the same
 * guarantee.
 */
suspend fun runResilientTicker(
    scope: CoroutineScope,
    intervalMs: Long,
    continueCondition: () -> Boolean,
    onTickFailure: (Throwable) -> Unit = {},
    onTick: () -> Unit,
) {
    while (scope.isActive && continueCondition()) {
        runCatching(onTick).onFailure(onTickFailure)
        delay(intervalMs)
    }
}
