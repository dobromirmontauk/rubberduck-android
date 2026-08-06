package com.montauk.voicecapture.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead vn-edu.56: [TooShortDecision.resolve]'s timing behavior, using
 * `runTest`'s virtual clock so the timeout path doesn't need a real 6s wait.
 */
class TooShortDecisionTest {

    @Test
    fun `shouldWarn false returns true immediately without touching the signal`() = runTest {
        // A signal that's never completed would hang forever if resolve()
        // ever awaited it -- proves the !shouldWarn early-return path never does.
        val neverCompletes = CompletableDeferred<Boolean>()

        val result = TooShortDecision.resolve(shouldWarn = false, saveAnywaySignal = neverCompletes, warningWindowMs = 6_000L)

        assertTrue(result)
    }

    @Test
    fun `save-anyway signal completing before the window elapses returns true`() = runTest {
        val signal = CompletableDeferred<Boolean>()
        signal.complete(true)

        val result = TooShortDecision.resolve(shouldWarn = true, saveAnywaySignal = signal, warningWindowMs = 6_000L)

        assertTrue(result)
    }

    @Test
    fun `timeout with no save-anyway signal defaults to discard (false)`() = runTest {
        val signal = CompletableDeferred<Boolean>() // never completed

        val result = TooShortDecision.resolve(shouldWarn = true, saveAnywaySignal = signal, warningWindowMs = 6_000L)

        assertFalse(result)
    }

    @Test
    fun `save-anyway tapped partway through the window still preserves the session`() = runTest {
        val signal = CompletableDeferred<Boolean>()
        // Fires well inside the 6s window (virtual time, via runTest's scheduler)
        // -- resolve() must return as soon as this completes, not wait out the timeout.
        launch {
            delay(2_000L)
            signal.complete(true)
        }

        val result = TooShortDecision.resolve(shouldWarn = true, saveAnywaySignal = signal, warningWindowMs = 6_000L)

        assertTrue(result)
    }
}
