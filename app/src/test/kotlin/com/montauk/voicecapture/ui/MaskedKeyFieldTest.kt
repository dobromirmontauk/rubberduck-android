package com.montauk.voicecapture.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bead vn-edu.48: Settings' key rows show a masked value with the last 4 characters visible. */
class MaskedKeyFieldTest {

    @Test
    fun `shows exactly four mask dots followed by the last 4 characters`() {
        assertEquals("••••3f2a", maskedLast4("sk-ant-api03-abcdef123456789xyz3f2a"))
    }

    @Test
    fun `a key shorter than 4 characters still only shows what it has`() {
        assertEquals("••••ab", maskedLast4("ab"))
    }

    @Test
    fun `never includes any characters beyond the last 4`() {
        val key = "sk-ant-super-secret-value-1234"
        val masked = maskedLast4(key)

        assertEquals(8, masked.length)
        assertEquals(false, masked.contains("secret"))
    }
}
