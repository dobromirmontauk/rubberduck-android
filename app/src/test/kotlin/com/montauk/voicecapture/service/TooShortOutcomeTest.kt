package com.montauk.voicecapture.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead vn-edu.56: [TooShortOutcome.resolveAndRoute] is what [RecordingService]
 * actually wires [runFinalizeWithWatchdog]/[UploadWorker.enqueue] (via
 * `finalize`) and [TooShortSessionDiscarder.discard] (via `discard`) into --
 * these tests are the "save-anyway path preserves finalize+upload" /
 * "discard by default" failing-first tests the bead calls for, using fake
 * callbacks so no real [com.montauk.voicecapture.audio.AudioEngine] is needed.
 */
class TooShortOutcomeTest {

    private class Spy {
        var finalizeCalled = false
        var discardCalled = false
        var warningResolvedCalled = false
    }

    @Test
    fun `save-anyway tapped -- finalize runs, discard never does`() = runTest {
        val spy = Spy()
        val signal = CompletableDeferred(true) // "Save anyway" already tapped

        TooShortOutcome.resolveAndRoute(
            shouldWarn = true,
            saveAnywaySignal = signal,
            warningWindowMs = 6_000L,
            onWarningResolved = { spy.warningResolvedCalled = true },
            finalize = { spy.finalizeCalled = true },
            discard = { spy.discardCalled = true },
        )

        assertTrue("save-anyway must preserve the normal finalize+upload path", spy.finalizeCalled)
        assertFalse("save-anyway must never also discard", spy.discardCalled)
        assertTrue(spy.warningResolvedCalled)
    }

    @Test
    fun `no save-anyway before the window elapses -- discard runs, finalize never does`() = runTest {
        val spy = Spy()
        val signal = CompletableDeferred<Boolean>() // never completed -- times out

        TooShortOutcome.resolveAndRoute(
            shouldWarn = true,
            saveAnywaySignal = signal,
            warningWindowMs = 6_000L,
            onWarningResolved = { spy.warningResolvedCalled = true },
            finalize = { spy.finalizeCalled = true },
            discard = { spy.discardCalled = true },
        )

        assertTrue("timing out on the warning must discard by default", spy.discardCalled)
        assertFalse("a discarded session must never also finalize/upload", spy.finalizeCalled)
        assertTrue(spy.warningResolvedCalled)
    }

    @Test
    fun `not warned at all -- finalize runs unconditionally, discard never does, warning state untouched`() = runTest {
        val spy = Spy()
        val signal = CompletableDeferred(true) // pre-completed, matching RecordingService's !shouldWarn wiring

        TooShortOutcome.resolveAndRoute(
            shouldWarn = false,
            saveAnywaySignal = signal,
            warningWindowMs = 6_000L,
            onWarningResolved = { spy.warningResolvedCalled = true },
            finalize = { spy.finalizeCalled = true },
            discard = { spy.discardCalled = true },
        )

        assertTrue(spy.finalizeCalled)
        assertFalse(spy.discardCalled)
        assertFalse("a session that never tripped the too-short rule must never touch the warning state", spy.warningResolvedCalled)
    }
}
