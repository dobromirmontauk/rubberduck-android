package com.montauk.voicecapture.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleResponseParserTest {

    @Test
    fun `a clean title passes through unchanged`() {
        assertEquals("Kitchen remodel bids and layout call", TitleResponseParser.parse("Kitchen remodel bids and layout call"))
    }

    @Test
    fun `null input yields null`() {
        assertNull(TitleResponseParser.parse(null))
    }

    @Test
    fun `blank input yields null`() {
        assertNull(TitleResponseParser.parse("   "))
    }

    @Test
    fun `wrapping double quotes are stripped`() {
        assertEquals("Kitchen remodel bids", TitleResponseParser.parse("\"Kitchen remodel bids\""))
    }

    @Test
    fun `wrapping single quotes are stripped`() {
        assertEquals("Kitchen remodel bids", TitleResponseParser.parse("'Kitchen remodel bids'"))
    }

    @Test
    fun `a markdown code fence is stripped`() {
        assertEquals("Kitchen remodel bids", TitleResponseParser.parse("```\nKitchen remodel bids\n```"))
    }

    @Test
    fun `a trailing period is stripped`() {
        assertEquals("Kitchen remodel bids", TitleResponseParser.parse("Kitchen remodel bids."))
    }

    @Test
    fun `multi-line output collapses to a single space-joined line`() {
        assertEquals("Kitchen remodel bids and layout", TitleResponseParser.parse("Kitchen remodel bids\nand layout"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("Kitchen remodel bids", TitleResponseParser.parse("   Kitchen remodel bids   "))
    }

    @Test
    fun `a title left blank after stripping quotes yields null`() {
        assertNull(TitleResponseParser.parse("\"\""))
    }

    @Test
    fun `an unexpectedly long response is clamped to 12 words`() {
        val longTitle = (1..20).joinToString(" ") { "word$it" }

        val parsed = TitleResponseParser.parse(longTitle)!!

        assertEquals((1..12).joinToString(" ") { "word$it" }, parsed)
    }

    @Test
    fun `a preamble-free short title within the word limit is untouched`() {
        val eightWords = (1..8).joinToString(" ") { "word$it" }
        assertEquals(eightWords, TitleResponseParser.parse(eightWords))
    }
}
