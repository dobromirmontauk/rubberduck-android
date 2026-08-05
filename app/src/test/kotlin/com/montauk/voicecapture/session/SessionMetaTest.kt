package com.montauk.voicecapture.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMetaTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `modes field encodes with the same key naming as mode event lines`() {
        val meta = SessionMeta(
            sessionId = "2026-08-05_1200_abcd",
            startedAt = "2026-08-05T12:00:00.000Z",
            durationMs = 5_000L,
            device = "Pixel 9",
            appVersion = "0.1.0",
            modes = listOf(SessionModeEntry(0L, "listen")),
        )

        val encoded = json.encodeToString(SessionMeta.serializer(), meta)
        val modes = Json.parseToJsonElement(encoded).jsonObject["modes"]!!.jsonArray

        assertEquals(1, modes.size)
        val entry = modes[0].jsonObject
        assertEquals(0, entry["t_ms"]?.toString()?.toInt())
        assertEquals("\"listen\"", entry["mode"].toString())
    }

    @Test
    fun `modes is absent-tolerant -- decoding an older meta json without it yields null`() {
        val legacyJson = """
            {"session_id":"2026-08-05_1200_abcd","started_at":"2026-08-05T12:00:00.000Z",
             "duration_ms":5000,"device":"Pixel 9","app_version":"0.1.0","stt":null,"schema_version":1}
        """.trimIndent()

        val meta = Json.decodeFromString(SessionMeta.serializer(), legacyJson)

        assertNull(meta.modes)
    }

    @Test
    fun `modes round-trips a multi-entry mode history in order`() {
        val modes = listOf(
            SessionModeEntry(0L, "listen"),
            SessionModeEntry(15_000L, "listen"),
        )
        val meta = SessionMeta(
            sessionId = "2026-08-05_1200_abcd",
            startedAt = "2026-08-05T12:00:00.000Z",
            durationMs = 20_000L,
            device = "Pixel 9",
            appVersion = "0.1.0",
            modes = modes,
        )

        val decoded = Json.decodeFromString(SessionMeta.serializer(), Json.encodeToString(SessionMeta.serializer(), meta))

        assertEquals(modes, decoded.modes)
        assertTrue(decoded.modes!!.map { it.tMs } == listOf(0L, 15_000L))
    }
}
