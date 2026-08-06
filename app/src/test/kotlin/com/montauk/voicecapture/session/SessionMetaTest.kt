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

    @Test
    fun `title defaults to null and encodes explicitly (encodeDefaults) like stt`() {
        val meta = SessionMeta(
            sessionId = "2026-08-05_1200_abcd",
            startedAt = "2026-08-05T12:00:00.000Z",
            durationMs = 5_000L,
            device = "Pixel 9",
            appVersion = "0.1.0",
        )

        val encoded = json.encodeToString(SessionMeta.serializer(), meta)
        val root = Json.parseToJsonElement(encoded).jsonObject

        assertNull(meta.title)
        assertTrue("title key must be present even when null", root.containsKey("title"))
        assertEquals("null", root["title"].toString())
    }

    @Test
    fun `title round-trips a generated title`() {
        val meta = SessionMeta(
            sessionId = "2026-08-05_1200_abcd",
            startedAt = "2026-08-05T12:00:00.000Z",
            durationMs = 5_000L,
            device = "Pixel 9",
            appVersion = "0.1.0",
            title = "Kitchen remodel bids and layout call",
        )

        val decoded = Json.decodeFromString(SessionMeta.serializer(), Json.encodeToString(SessionMeta.serializer(), meta))

        assertEquals("Kitchen remodel bids and layout call", decoded.title)
    }

    @Test
    fun `title is absent-tolerant -- decoding an older meta json without it yields null`() {
        val legacyJson = """
            {"session_id":"2026-08-05_1200_abcd","started_at":"2026-08-05T12:00:00.000Z",
             "duration_ms":5000,"device":"Pixel 9","app_version":"0.1.0","stt":null,"schema_version":1}
        """.trimIndent()

        val meta = Json.decodeFromString(SessionMeta.serializer(), legacyJson)

        assertNull(meta.title)
    }

    @Test
    fun `recordedMs defaults to null and encodes explicitly (encodeDefaults) like title and stt`() {
        val meta = SessionMeta(
            sessionId = "2026-08-05_1200_abcd",
            startedAt = "2026-08-05T12:00:00.000Z",
            durationMs = 5_000L,
            device = "Pixel 9",
            appVersion = "0.1.0",
        )

        val encoded = json.encodeToString(SessionMeta.serializer(), meta)
        val root = Json.parseToJsonElement(encoded).jsonObject

        assertNull(meta.recordedMs)
        assertTrue("recorded_ms key must be present even when null", root.containsKey("recorded_ms"))
        assertEquals("null", root["recorded_ms"].toString())
    }

    @Test
    fun `recordedMs round-trips a trimmed duration`() {
        val meta = SessionMeta(
            sessionId = "2026-08-05_1200_abcd",
            startedAt = "2026-08-05T12:00:00.000Z",
            durationMs = 120_000L,
            device = "Pixel 9",
            appVersion = "0.1.0",
            recordedMs = 90_000L,
        )

        val decoded = Json.decodeFromString(SessionMeta.serializer(), Json.encodeToString(SessionMeta.serializer(), meta))

        assertEquals(90_000L, decoded.recordedMs)
    }

    @Test
    fun `recordedMs is absent-tolerant -- decoding an older meta json without it yields null`() {
        val legacyJson = """
            {"session_id":"2026-08-05_1200_abcd","started_at":"2026-08-05T12:00:00.000Z",
             "duration_ms":5000,"device":"Pixel 9","app_version":"0.1.0","stt":null,"schema_version":1}
        """.trimIndent()

        val meta = Json.decodeFromString(SessionMeta.serializer(), legacyJson)

        assertNull(meta.recordedMs)
    }
}
