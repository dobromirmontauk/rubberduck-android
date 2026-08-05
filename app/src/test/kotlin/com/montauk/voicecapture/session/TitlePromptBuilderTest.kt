package com.montauk.voicecapture.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlePromptBuilderTest {

    @Test
    fun `empty transcript yields null -- nothing to title`() {
        assertNull(TitlePromptBuilder.buildTranscriptExcerpt(emptyList()))
    }

    @Test
    fun `a short transcript is included in full, no elision`() {
        val lines = listOf("Hey there", "How's the kitchen remodel going", "Pretty good actually")

        val excerpt = TitlePromptBuilder.buildTranscriptExcerpt(lines)!!

        assertEquals(lines.joinToString("\n"), excerpt)
        assertTrue(!excerpt.contains("[...]"))
    }

    @Test
    fun `exactly 25 lines (the head+tail boundary) is included in full`() {
        val lines = (1..25).map { "line $it" }

        val excerpt = TitlePromptBuilder.buildTranscriptExcerpt(lines)!!

        assertEquals(lines.joinToString("\n"), excerpt)
    }

    @Test
    fun `a long transcript keeps the first 15 and last 10 lines with an elision marker between`() {
        val lines = (1..40).map { "line $it" }

        val excerpt = TitlePromptBuilder.buildTranscriptExcerpt(lines)!!
        val excerptLines = excerpt.split("\n")

        assertEquals((1..15).map { "line $it" }, excerptLines.take(15))
        assertEquals("[...]", excerptLines[15])
        assertEquals((31..40).map { "line $it" }, excerptLines.takeLast(10))
        assertEquals(26, excerptLines.size)
    }
}
