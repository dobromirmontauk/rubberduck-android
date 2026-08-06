package com.montauk.voicecapture.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptWordCountTest {

    @Test
    fun `empty list is zero words`() {
        assertEquals(0, TranscriptWordCount.count(emptyList()))
    }

    @Test
    fun `counts words across multiple final lines`() {
        val lines = listOf(
            TranscriptLine("hello there", startMs = 0L, endMs = 900L),
            TranscriptLine("how are you", startMs = 900L, endMs = 1_800L),
        )
        assertEquals(5, TranscriptWordCount.count(lines))
    }

    @Test
    fun `collapses runs of whitespace rather than counting them as words`() {
        val lines = listOf(TranscriptLine("hello   there  friend", startMs = 0L, endMs = 900L))
        assertEquals(3, TranscriptWordCount.count(lines))
    }

    @Test
    fun `a single blank line contributes zero words`() {
        val lines = listOf(TranscriptLine("   ", startMs = 0L, endMs = 900L))
        assertEquals(0, TranscriptWordCount.count(lines))
    }
}
