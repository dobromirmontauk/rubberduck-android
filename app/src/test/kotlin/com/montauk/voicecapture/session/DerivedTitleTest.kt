package com.montauk.voicecapture.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DerivedTitleTest {

    @Test
    fun `null or blank text yields untitled`() {
        assertEquals("untitled", DerivedTitle.from(null))
        assertEquals("untitled", DerivedTitle.from("   "))
    }

    @Test
    fun `takes first five words`() {
        assertEquals(
            "we need to talk about",
            DerivedTitle.from("we need to talk about the budget for next quarter"),
        )
    }

    @Test
    fun `shorter than five words is returned as-is`() {
        assertEquals("quick note here", DerivedTitle.from("quick note here"))
    }

    @Test
    fun `skips leading filler words`() {
        assertEquals("the meeting went well today", DerivedTitle.from("um so the meeting went well today"))
    }

    @Test
    fun `all filler yields untitled`() {
        assertEquals("untitled", DerivedTitle.from("um uh so okay"))
    }
}
