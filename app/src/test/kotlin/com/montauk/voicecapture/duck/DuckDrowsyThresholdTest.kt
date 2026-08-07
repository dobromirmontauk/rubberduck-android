package com.montauk.voicecapture.duck

import com.montauk.voicecapture.audio.autoPauseFillFraction
import com.montauk.voicecapture.service.RecordingActivityState
import com.montauk.voicecapture.settings.AppSecretsStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end regression coverage for bead asn-3h6: exercises the REAL
 * [autoPauseFillFraction] math (the same quiet-tracking
 * [com.montauk.voicecapture.service.RecordingService] and the pause
 * button's gradient fill both use) feeding [toDuckState], rather than
 * [DuckActivityMappingTest]'s hand-picked fraction values -- this is what
 * actually pins down "5s of quiet -> DROWSY, 10s -> auto-pause" at the
 * DEFAULT threshold, and proves the duck state only ever moves off
 * ATTENTIVE/DROWSY because of the *same* signal the auto-pause countdown
 * and the pause-button fill already derive, not a second ad-hoc timer.
 */
class DuckDrowsyThresholdTest {

    private val totalThresholdMs = AppSecretsStore.DEFAULT_AUTO_PAUSE_THRESHOLD_MS // 10_000L
    private val fillDurationMs = AppSecretsStore.AUTO_PAUSE_FILL_DURATION_MS // 5_000L

    private fun duckStateAtQuietMs(continuousQuietMs: Long): DuckState {
        val fraction = autoPauseFillFraction(
            continuousQuietMs = continuousQuietMs,
            totalThresholdMs = totalThresholdMs,
            fillDurationMs = fillDurationMs,
            enabled = true,
        )
        return RecordingActivityState.QUIET.toDuckState(fraction)
    }

    @Test
    fun `a fresh session (0ms quiet) is ATTENTIVE`() {
        assertEquals(DuckState.ATTENTIVE, duckStateAtQuietMs(0L))
    }

    @Test
    fun `the entire leading invisible span (up to and including 5000ms) stays ATTENTIVE`() {
        assertEquals(DuckState.ATTENTIVE, duckStateAtQuietMs(0L))
        assertEquals(DuckState.ATTENTIVE, duckStateAtQuietMs(2_500L))
        assertEquals(DuckState.ATTENTIVE, duckStateAtQuietMs(4_999L))
        // Exactly at the 5s boundary the fill fraction is still 0f (fraction
        // only becomes positive once quiet exceeds fillStartMs) -- ATTENTIVE
        // through this instant, DROWSY the instant after.
        assertEquals(DuckState.ATTENTIVE, duckStateAtQuietMs(5_000L))
    }

    @Test
    fun `just past 5000ms of quiet flips to DROWSY`() {
        assertEquals(DuckState.DROWSY, duckStateAtQuietMs(5_001L))
    }

    @Test
    fun `mid-fill window (e_g_ 7500ms, half filled) is DROWSY`() {
        assertEquals(DuckState.DROWSY, duckStateAtQuietMs(7_500L))
    }

    @Test
    fun `right up to auto-pause firing (9999ms) is still DROWSY, not SLEEP`() {
        // toDuckState never produces SLEEP from QUIET alone -- SLEEP only
        // comes from RecordingActivityState actually flipping to
        // AUTO_PAUSED/USER_PAUSED (RecordingService's job, not this
        // mapping's), which happens once continuousQuietMs reaches
        // totalThresholdMs (10_000ms), one ms after this.
        assertEquals(DuckState.DROWSY, duckStateAtQuietMs(9_999L))
    }

    @Test
    fun `auto-pause disabled keeps the fraction at zero, so QUIET never drifts into DROWSY on its own`() {
        val fraction = autoPauseFillFraction(
            continuousQuietMs = 9_999L,
            totalThresholdMs = totalThresholdMs,
            fillDurationMs = fillDurationMs,
            enabled = false,
        )
        assertEquals(DuckState.ATTENTIVE, RecordingActivityState.QUIET.toDuckState(fraction))
    }

    @Test
    fun `a larger configured threshold (e_g_ 30s) still starts the fill exactly fillDurationMs before it fires`() {
        val largerThresholdMs = 30_000L
        val fraction24999 = autoPauseFillFraction(24_999L, largerThresholdMs, fillDurationMs, enabled = true)
        val fraction25001 = autoPauseFillFraction(25_001L, largerThresholdMs, fillDurationMs, enabled = true)
        assertEquals(DuckState.ATTENTIVE, RecordingActivityState.QUIET.toDuckState(fraction24999))
        assertEquals(DuckState.DROWSY, RecordingActivityState.QUIET.toDuckState(fraction25001))
    }
}
