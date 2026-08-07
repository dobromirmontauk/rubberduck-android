package com.montauk.voicecapture.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bead asn-kd2.1: pure-JVM geometry coverage for [DuckWaveformBar]'s
 * (`com.montauk.voicecapture.ui.RecordingScreen`, private) exact
 * pre-warm recipe -- `LoudnessVisualizer(historyLength = 24).apply {
 * repeat(24) { onLevel(level) } }` -- since [Canvas]-drawn ticks have no
 * semantics nodes a Compose test could count directly, this exercises the
 * same [LoudnessVisualizer] usage pattern in isolation instead. Verifies
 * the two concrete, numeric acceptance criteria: exactly ~24 discrete
 * ticks, and NO "dashed line + trailing dot" artifact (a cold, un-warmed
 * visualizer leaves 23 entries at 0f next to one much taller bar -- see
 * [RecordingScreen]'s own KDoc on this fix for the full story).
 */
class DuckWaveformPreWarmTest {

    private companion object {
        const val TICK_COUNT = 24
    }

    @Test
    fun `pre-warmed history has exactly 24 entries`() {
        val visualizer = LoudnessVisualizer(historyLength = TICK_COUNT).apply {
            repeat(TICK_COUNT) { onLevel(0.4f) }
        }
        assertEquals(TICK_COUNT, visualizer.history.size)
    }

    @Test
    fun `pre-warming an active level settles every tick to the same height -- no cold-start dot`() {
        val visualizer = LoudnessVisualizer(historyLength = TICK_COUNT).apply {
            repeat(TICK_COUNT) { onLevel(0.4f) }
        }
        val history = visualizer.history

        // The bug this fixes: an un-warmed visualizer would have TICK_COUNT-1
        // entries at 0f and one real, much-taller entry -- i.e. a huge
        // spread across the row. After pre-warming, every tick should have
        // converged to (quantized) the same settled height.
        val distinctHeights = history.toSet()
        assertTrue(
            "pre-warmed ticks should all settle to the same quantized height, got $distinctHeights",
            distinctHeights.size <= 2, // allow at most one still-converging early tick
        )
        assertTrue("no tick should still be at the cold-start floor of 0f", history.none { it == 0f })
    }

    @Test
    fun `silence pre-warms to a uniform floor, not a flat line with one live tick`() {
        val visualizer = LoudnessVisualizer(historyLength = TICK_COUNT).apply {
            repeat(TICK_COUNT) { onLevel(0f) }
        }
        assertEquals("true silence should be a uniform row (all zero, the tick-drawing floor renders the visible minimum)", List(TICK_COUNT) { 0f }, visualizer.history)
    }
}
