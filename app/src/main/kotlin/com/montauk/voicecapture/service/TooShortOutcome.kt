package com.montauk.voicecapture.service

import kotlinx.coroutines.Deferred

/**
 * Bead vn-edu.56: the too-short decision + branch, extracted from
 * [RecordingService.endRecording] purely so the "save-anyway preserves the
 * normal finalize path; discard skips it entirely" contract is unit-testable
 * (with fake [finalize]/[discard] callbacks) without spinning up an actual
 * foreground service / [com.montauk.voicecapture.audio.AudioEngine].
 * [finalize] and [discard] are mutually exclusive -- exactly one runs.
 */
object TooShortOutcome {
    suspend fun resolveAndRoute(
        shouldWarn: Boolean,
        saveAnywaySignal: Deferred<Boolean>,
        warningWindowMs: Long,
        onWarningResolved: () -> Unit = {},
        finalize: suspend () -> Unit,
        discard: () -> Unit,
    ) {
        val saveAnyway = TooShortDecision.resolve(shouldWarn, saveAnywaySignal, warningWindowMs)
        if (shouldWarn) onWarningResolved()
        if (saveAnyway) finalize() else discard()
    }
}
