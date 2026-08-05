package com.montauk.voicecapture.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessVisualizerTest {

    @Test
    fun `history starts fully empty at the configured length`() {
        val visualizer = LoudnessVisualizer(historyLength = 5)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), visualizer.history)
    }

    @Test
    fun `silence stays at zero -- reads visibly empty`() {
        val visualizer = LoudnessVisualizer()
        repeat(20) {
            val bar = visualizer.onLevel(0f)
            assertEquals(0f, bar, 0.0001f)
        }
        assertTrue(visualizer.history.all { it == 0f })
    }

    @Test
    fun `level at the silence-detector threshold also renders empty`() {
        // SilenceDetector's rmsThreshold is 0.02f (~-34dBFS), which is exactly
        // this class's floorDb -- the meter's "empty" should line up with the
        // "(silence)" hint's threshold, not fall just short of it.
        val visualizer = LoudnessVisualizer()
        repeat(10) { visualizer.onLevel(0.02f) }
        assertEquals(0f, visualizer.history.last(), 0.0001f)
    }

    @Test
    fun `full-scale tone saturates to 1`() {
        val visualizer = LoudnessVisualizer()
        repeat(10) { visualizer.onLevel(1f) }
        assertEquals(1f, visualizer.history.last(), 0.0001f)
    }

    @Test
    fun `moderate speech level registers well above a linear mapping would predict`() {
        // Raw RMS 0.1 is only 10% of full scale, but real speech rarely
        // approaches full scale -- the whole point of the dB curve is that a
        // level like this should read as clearly more than a sliver.
        val visualizer = LoudnessVisualizer()
        repeat(10) { visualizer.onLevel(0.1f) }
        val bar = visualizer.history.last()
        assertTrue("expected a mid-range bar, got $bar", bar in 0.3f..0.6f)
    }

    @Test
    fun `attack is fast -- a loud window moves the bar most of the way immediately`() {
        val visualizer = LoudnessVisualizer()
        val firstBar = visualizer.onLevel(1f)
        assertTrue("expected fast attack, got $firstBar", firstBar > 0.7f)
    }

    @Test
    fun `decay is slow -- returning to silence after loud doesn't snap to zero`() {
        val visualizer = LoudnessVisualizer()
        repeat(10) { visualizer.onLevel(1f) } // settle at loud
        val afterOneQuietWindow = visualizer.onLevel(0f)
        assertTrue(
            "expected a graceful decay, not an instant drop, got $afterOneQuietWindow",
            afterOneQuietWindow > 0.5f,
        )
    }

    @Test
    fun `decay is slower than attack for the same-size step`() {
        val attackVisualizer = LoudnessVisualizer()
        val afterAttack = attackVisualizer.onLevel(1f) // 0 -> loud, one window

        val decayVisualizer = LoudnessVisualizer()
        repeat(10) { decayVisualizer.onLevel(1f) }
        val beforeDecay = decayVisualizer.history.last()
        val afterDecay = decayVisualizer.onLevel(0f) // loud -> 0, one window
        val decayStep = beforeDecay - afterDecay
        val attackStep = afterAttack - 0f

        assertTrue(
            "expected decay step ($decayStep) to be smaller than attack step ($attackStep)",
            decayStep < attackStep,
        )
    }

    @Test
    fun `quantization collapses to a small number of distinct heights`() {
        val visualizer = LoudnessVisualizer(levelSteps = 12, attackPerWindow = 1f, decayPerWindow = 1f)
        val seen = mutableSetOf<Float>()
        // Sweep the whole input range; with instant attack/decay every step
        // reflects the quantizer directly.
        for (i in 0..100) {
            seen.add(visualizer.onLevel(i / 100f))
        }
        assertTrue("expected at most 13 distinct heights (steps 0..12), got ${seen.size}: $seen", seen.size <= 13)
    }

    @Test
    fun `history is a fixed-length FIFO -- oldest value is dropped, newest lands at the tail`() {
        val visualizer = LoudnessVisualizer(historyLength = 4, attackPerWindow = 1f, decayPerWindow = 1f)
        visualizer.onLevel(1f)
        visualizer.onLevel(0f)
        visualizer.onLevel(1f)
        visualizer.onLevel(0f)
        assertEquals(4, visualizer.history.size)
        // One more window should push out the oldest entry, not grow the list.
        visualizer.onLevel(1f)
        assertEquals(4, visualizer.history.size)
        assertTrue("expected the newest (loud) window at the tail", visualizer.history.last() > 0.5f)
    }

    @Test
    fun `higher raw rms never produces a lower bar once settled`() {
        val visualizer = LoudnessVisualizer(attackPerWindow = 1f, decayPerWindow = 1f)
        var previous = -1f
        for (raw in listOf(0f, 0.01f, 0.02f, 0.05f, 0.1f, 0.2f, 0.4f, 0.8f, 1f)) {
            val bar = visualizer.onLevel(raw)
            assertTrue("expected monotonic non-decreasing bars, $bar < $previous at raw=$raw", bar >= previous)
            previous = bar
        }
    }
}
