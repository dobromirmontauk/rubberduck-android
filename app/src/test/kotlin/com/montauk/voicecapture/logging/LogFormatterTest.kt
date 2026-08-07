package com.montauk.voicecapture.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class LogFormatterTest {

    @Test
    fun `formats component and event with no fields`() {
        val line = LogFormatter.format(nowMs = 1_000L, component = "Service", event = "start", fields = emptyList())
        assertEquals("ts=1000 component=Service event=start", line)
    }

    @Test
    fun `appends key=value fields in the order given`() {
        val line = LogFormatter.format(
            nowMs = 1_000L,
            component = "RecordingActivityState",
            event = "transition",
            fields = listOf("from" to "SPEAKING", "to" to "QUIET"),
        )
        assertEquals("ts=1000 component=RecordingActivityState event=transition from=SPEAKING to=QUIET", line)
    }

    @Test
    fun `quotes a value containing whitespace so a whitespace split still sees one token`() {
        val line = LogFormatter.format(
            nowMs = 1_000L,
            component = "Upload",
            event = "failed",
            fields = listOf("reason" to "network unavailable"),
        )
        assertEquals("ts=1000 component=Upload event=failed reason=\"network unavailable\"", line)
    }

    @Test
    fun `renders a null value literally rather than throwing`() {
        val line = LogFormatter.format(
            nowMs = 1_000L,
            component = "Tags",
            event = "swap",
            fields = listOf("tagId" to null),
        )
        assertEquals("ts=1000 component=Tags event=swap tagId=null", line)
    }

    @Test
    fun `non-string values render via toString without quoting`() {
        val line = LogFormatter.format(
            nowMs = 1_000L,
            component = "RecordingActivityState",
            event = "fill_milestone",
            fields = listOf("percent" to 50, "speaking" to false),
        )
        assertEquals("ts=1000 component=RecordingActivityState event=fill_milestone percent=50 speaking=false", line)
    }
}
