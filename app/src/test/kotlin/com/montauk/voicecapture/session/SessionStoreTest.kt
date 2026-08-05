package com.montauk.voicecapture.session

import java.io.File
import java.nio.file.Files
import java.util.Date
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionStoreTest {

    private lateinit var baseDir: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("session-store-test").toFile()
        store = SessionStore(baseDir)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun `createSession makes a directory with a valid session id`() {
        val handle = store.createSession(Date())

        assertTrue(SessionId.isValid(handle.sessionId))
        assertTrue(handle.dir.exists() && handle.dir.isDirectory)
        assertEquals(File(baseDir, handle.sessionId), handle.dir)
    }

    @Test
    fun `writeMeta produces valid, schema-conformant meta json plus an empty transcript file`() {
        val handle = store.createSession(Date())
        // Simulate AudioEngine having already finalized the audio before writeMeta runs.
        store.oggFile(handle.dir).writeText("fake-ogg-bytes")

        store.writeMeta(handle, durationMs = 754_321L, deviceModel = "Pixel 9", appVersion = "0.1.0")

        val metaFile = store.metaFile(handle.dir)
        assertTrue(metaFile.exists())

        val meta = Json.decodeFromString(SessionMeta.serializer(), metaFile.readText())
        assertEquals(handle.sessionId, meta.sessionId)
        assertEquals(754_321L, meta.durationMs)
        assertEquals("Pixel 9", meta.device)
        assertEquals("0.1.0", meta.appVersion)
        assertNull(meta.stt)
        assertEquals(1, meta.schemaVersion)

        assertTrue("live-transcript.jsonl should exist", store.transcriptFile(handle.dir).exists())
        assertEquals(0L, store.transcriptFile(handle.dir).length())

        assertEquals(UploadState.LOCAL, store.readUploadState(handle.dir))
    }

    @Test
    fun `findUnfinalizedSessions finds a wal without a matching ogg`() {
        val orphaned = store.createSession(Date())
        store.walFile(orphaned.dir).writeText("partial-wal-bytes")

        val finalized = store.createSession(Date())
        store.walFile(finalized.dir).writeText("wal-bytes")
        store.oggFile(finalized.dir).writeText("ogg-bytes")

        val unfinalized = store.findUnfinalizedSessions()

        assertEquals(1, unfinalized.size)
        assertEquals(orphaned.sessionId, unfinalized.first().sessionId)
    }

    @Test
    fun `listSessions only returns sessions with meta json, newest first`() {
        val older = store.createSession(Date(1_000))
        store.oggFile(older.dir).writeText("ogg")
        store.writeMeta(older, durationMs = 1_000L, deviceModel = "d", appVersion = "v")

        // No meta.json written for this one -- still mid-recording, shouldn't show up yet.
        store.createSession(Date(2_000))

        val newer = store.createSession(Date(3_000))
        store.oggFile(newer.dir).writeText("ogg")
        store.writeMeta(newer, durationMs = 2_000L, deviceModel = "d", appVersion = "v")

        val sessions = store.listSessions()

        assertEquals(listOf(newer.sessionId, older.sessionId), sessions.map { it.sessionId })
    }
}
