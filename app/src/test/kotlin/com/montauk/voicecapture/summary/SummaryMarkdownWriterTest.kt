package com.montauk.voicecapture.summary

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryMarkdownWriterTest {

    @Test
    fun `no bullets renders an empty string, not a header-only stub`() {
        assertEquals("", SummaryMarkdownWriter.render(emptyList()))
    }

    @Test
    fun `bullets render as a markdown list, one per line`() {
        val rendered = SummaryMarkdownWriter.render(listOf("first thing", "second thing"))

        assertEquals("- first thing\n- second thing\n", rendered)
    }
}
