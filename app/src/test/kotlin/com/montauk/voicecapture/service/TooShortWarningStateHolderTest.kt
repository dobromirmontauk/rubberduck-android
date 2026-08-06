package com.montauk.voicecapture.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead vn-edu.56: [TooShortWarningStateHolder]'s UI-state emission --
 * the "UI-state emission for the warning" failing-first test the bead calls
 * for. [TooShortWarningStateHolder] is a process-global singleton (matching
 * [RecordingStateHolder]/[TranscriptStateHolder]'s existing pattern), so each
 * test clears it afterward to avoid leaking a pending decision across tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TooShortWarningStateHolderTest {

    @After
    fun tearDown() {
        TooShortWarningStateHolder.clearWarning()
    }

    @Test
    fun `beginWarning publishes a non-null state carrying the session id`() {
        TooShortWarningStateHolder.beginWarning("2026-08-05_1200_ab12")

        assertEquals(TooShortWarningUiState("2026-08-05_1200_ab12"), TooShortWarningStateHolder.state.value)
    }

    @Test
    fun `clearWarning resets state back to null`() {
        TooShortWarningStateHolder.beginWarning("2026-08-05_1200_ab12")

        TooShortWarningStateHolder.clearWarning()

        assertNull(TooShortWarningStateHolder.state.value)
    }

    @Test
    fun `saveAnyway completes the pending decision with true`() {
        val deferred = TooShortWarningStateHolder.beginWarning("2026-08-05_1200_ab12")

        TooShortWarningStateHolder.saveAnyway()

        assertTrue(deferred.isCompleted)
        assertEquals(true, deferred.getCompleted())
    }

    @Test
    fun `saveAnyway with no pending decision is a no-op`() {
        // Nothing pending -- must not throw.
        TooShortWarningStateHolder.saveAnyway()

        assertNull(TooShortWarningStateHolder.state.value)
    }

    @Test
    fun `a new beginWarning does not resolve a stale prior decision`() {
        val firstDeferred = TooShortWarningStateHolder.beginWarning("session-a")

        TooShortWarningStateHolder.beginWarning("session-b")

        assertEquals(TooShortWarningUiState("session-b"), TooShortWarningStateHolder.state.value)
        assertTrue("the first session's deferred must not have been resolved by starting a second warning", !firstDeferred.isCompleted)
    }
}
