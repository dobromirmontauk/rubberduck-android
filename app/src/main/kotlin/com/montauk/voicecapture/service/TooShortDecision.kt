package com.montauk.voicecapture.service

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bead vn-edu.56: resolves whether a session that tripped [TooShortPolicy]
 * actually gets discarded or saved, given the caller's warning window and a
 * "Save anyway" signal. Split out from [RecordingService] purely so this
 * timing logic is unit-testable with a virtual-time `TestScope`/`runTest`
 * (no Android/Robolectric dependency) rather than only exercisable through
 * an actual foreground service.
 */
object TooShortDecision {
    /**
     * [shouldWarn] is [TooShortPolicy.shouldDiscard]'s verdict, computed once
     * at Stop-time. When false, this returns `true` (save normally) without
     * ever touching [saveAnywaySignal] or waiting at all.
     *
     * When true, awaits [saveAnywaySignal] for up to [warningWindowMs]:
     *  - the signal completing (with any value) before the window elapses
     *    returns that value -- [TooShortWarningStateHolder.saveAnyway] always
     *    completes it `true`, so in practice this path always means "save".
     *  - the window elapsing first returns `false` ("Save anyway" was never
     *    tapped -- discard), matching the spec's "default outcome = discard".
     */
    suspend fun resolve(shouldWarn: Boolean, saveAnywaySignal: Deferred<Boolean>, warningWindowMs: Long): Boolean {
        if (!shouldWarn) return true
        return withTimeoutOrNull(warningWindowMs) { saveAnywaySignal.await() } ?: false
    }
}
