package com.montauk.voicecapture.summary

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendOnlyBulletMergeTest {

    @Test
    fun `empty previous takes the whole proposed list as new`() {
        val result = AppendOnlyBulletMerge.merge(emptyList(), listOf("first thing", "second thing"))

        assertEquals(listOf("first thing", "second thing"), result)
    }

    @Test
    fun `a well-behaved response appends only the genuinely new tail`() {
        val previous = listOf("first thing", "second thing")
        val proposed = listOf("first thing", "second thing", "third thing")

        val result = AppendOnlyBulletMerge.merge(previous, proposed)

        assertEquals(listOf("first thing", "second thing", "third thing"), result)
    }

    @Test
    fun `a gratuitous rewrite of a stable bullet is reverted to the old text`() {
        val previous = listOf("first thing", "second thing")
        // Model reworded "first thing" despite being told not to, and also added something new.
        val proposed = listOf("the first thing, reworded", "second thing", "third thing")

        val result = AppendOnlyBulletMerge.merge(previous, proposed)

        assertEquals(listOf("first thing", "second thing", "third thing"), result)
    }

    @Test
    fun `no growth at all returns previous unchanged even if the model rewrote everything`() {
        val previous = listOf("first thing", "second thing")
        val proposed = listOf("first thing reworded", "second thing reworded")

        val result = AppendOnlyBulletMerge.merge(previous, proposed)

        assertEquals(previous, result)
    }

    @Test
    fun `a shorter response than previous is ignored entirely -- previous is never shrunk`() {
        val previous = listOf("first thing", "second thing", "third thing")
        val proposed = listOf("first thing", "second thing")

        val result = AppendOnlyBulletMerge.merge(previous, proposed)

        assertEquals(previous, result)
    }

    @Test
    fun `a duplicate of an existing bullet re-emitted in the tail is dropped, not added again`() {
        val previous = listOf("discussed the kitchen remodel budget")
        // Model re-emits a near-duplicate (different case/whitespace) alongside a genuinely new one.
        val proposed = listOf(
            "discussed the kitchen remodel budget",
            "  Discussed The Kitchen Remodel Budget  ",
            "picked a contractor for the remodel",
        )

        val result = AppendOnlyBulletMerge.merge(previous, proposed)

        assertEquals(listOf("discussed the kitchen remodel budget", "picked a contractor for the remodel"), result)
    }

    @Test
    fun `blank candidates in the new tail are dropped`() {
        val previous = listOf("first thing")
        val proposed = listOf("first thing", "   ", "second thing")

        val result = AppendOnlyBulletMerge.merge(previous, proposed)

        assertEquals(listOf("first thing", "second thing"), result)
    }

    @Test
    fun `multiple genuinely new bullets in one round are all kept, in order`() {
        val previous = listOf("first thing")
        val proposed = listOf("first thing", "second thing", "third thing")

        val result = AppendOnlyBulletMerge.merge(previous, proposed)

        assertEquals(listOf("first thing", "second thing", "third thing"), result)
    }
}
