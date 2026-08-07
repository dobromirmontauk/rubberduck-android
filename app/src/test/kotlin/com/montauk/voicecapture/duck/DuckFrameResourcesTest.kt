package com.montauk.voicecapture.duck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asset-presence check for bead asn-5w3's 8-pose engine: every [DuckFrame]
 * token must resolve to a distinct, valid (non-zero) Android resource id --
 * catches a token added to the enum without a matching
 * `res/drawable-nodpi/duck_*.png` + [DuckFrameResources] mapping before it
 * ships, without needing an instrumented/Robolectric test (resource ids are
 * plain compile-time `Int` constants).
 */
class DuckFrameResourcesTest {

    @Test
    fun `exactly 8 pose tokens exist -- one per base state plus one per pulse`() {
        assertEquals(8, DuckFrame.values().size)
    }

    @Test
    fun `every DuckFrame resolves to a valid, distinct drawable resource`() {
        val resIds = DuckFrame.values().map { it.drawableRes() }
        resIds.forEach { assertTrue("drawable res id should be non-zero", it != 0) }
        assertEquals("each DuckFrame should map to its own drawable", resIds.size, resIds.toSet().size)
    }
}
