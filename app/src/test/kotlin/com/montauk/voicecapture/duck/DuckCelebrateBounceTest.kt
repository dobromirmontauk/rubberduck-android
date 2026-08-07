package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Test

class DuckCelebrateBounceTest {

    private val duration = 600L

    // --- celebrate bounce timing (2 bounces over the pulse duration) ---

    @Test
    fun `offset is zero at the very start and very end of the pulse`() {
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(0L, duration), 0.001f)
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(duration, duration), 0.001f)
    }

    @Test
    fun `offset touches back down to zero at the midpoint, between the two bounces`() {
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(duration / 2, duration), 0.001f)
    }

    @Test
    fun `offset peaks at the apex of each of the two bounces`() {
        // First bounce apex at 1/4 of the duration, second at 3/4.
        assertEquals(1f, DuckCelebrateBounce.offsetFraction(duration / 4, duration), 0.001f)
        assertEquals(1f, DuckCelebrateBounce.offsetFraction(duration * 3 / 4, duration), 0.001f)
    }

    @Test
    fun `offset is out of range before the pulse starts or after it ends`() {
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(-10L, duration), 0.001f)
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(duration + 50L, duration), 0.001f)
    }

    @Test
    fun `a zero-length duration never produces a nonzero offset`() {
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(0L, 0L), 0.001f)
    }

    // --- reduced-motion static fallback ---

    @Test
    fun `reducedMotion collapses the curve to zero at every elapsed time, including bounce apexes`() {
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(0L, duration, reducedMotion = true), 0.001f)
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(duration / 4, duration, reducedMotion = true), 0.001f)
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(duration / 2, duration, reducedMotion = true), 0.001f)
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(duration * 3 / 4, duration, reducedMotion = true), 0.001f)
        assertEquals(0f, DuckCelebrateBounce.offsetFraction(duration, duration, reducedMotion = true), 0.001f)
    }
}
