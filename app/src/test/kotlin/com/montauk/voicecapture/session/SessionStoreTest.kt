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

    @Test
    fun `listSessions derives a title from the first final transcript line`() {
        val handle = store.createSession(Date())
        store.oggFile(handle.dir).writeText("ogg")
        store.writeMeta(handle, durationMs = 1_000L, deviceModel = "d", appVersion = "v")
        store.transcriptFile(handle.dir).writeText(
            LiveTranscriptWriter.encodeLine(LiveTranscriptLine(0L, 2_000L, "we need to talk about the roadmap", final = true)) + "\n",
        )

        val summary = store.listSessions().first()

        assertEquals("we need to talk about", summary.title)
    }

    @Test
    fun `listSessions falls back to untitled with no transcript`() {
        val handle = store.createSession(Date())
        store.oggFile(handle.dir).writeText("ogg")
        store.writeMeta(handle, durationMs = 1_000L, deviceModel = "d", appVersion = "v")

        val summary = store.listSessions().first()

        assertEquals(DerivedTitle.UNTITLED, summary.title)
    }

    @Test
    fun `localSessionIds returns only sessions still marked LOCAL`() {
        val local = store.createSession(Date(1_000))
        store.oggFile(local.dir).writeText("ogg")
        store.writeMeta(local, durationMs = 1_000L, deviceModel = "d", appVersion = "v")
        // writeMeta defaults a fresh session to LOCAL -- no explicit setUploadState needed.

        val queued = store.createSession(Date(2_000))
        store.oggFile(queued.dir).writeText("ogg")
        store.writeMeta(queued, durationMs = 1_000L, deviceModel = "d", appVersion = "v")
        store.setUploadState(queued.dir, UploadState.QUEUED)

        val uploaded = store.createSession(Date(3_000))
        store.oggFile(uploaded.dir).writeText("ogg")
        store.writeMeta(uploaded, durationMs = 1_000L, deviceModel = "d", appVersion = "v")
        store.setUploadState(uploaded.dir, UploadState.UPLOADED)

        assertEquals(listOf(local.sessionId), store.localSessionIds())
    }

    @Test
    fun `readMeta returns null for an unknown session`() {
        assertNull(store.readMeta("2026-01-01_0000_zzzz"))
    }

    @Test
    fun `readMeta round-trips a written session`() {
        val handle = store.createSession(Date())
        store.oggFile(handle.dir).writeText("ogg")
        store.writeMeta(handle, durationMs = 5_000L, deviceModel = "d", appVersion = "v")

        val meta = store.readMeta(handle.sessionId)

        assertEquals(handle.sessionId, meta?.sessionId)
        assertEquals(5_000L, meta?.durationMs)
    }

    @Test
    fun `readTranscriptLines parses every final line and skips blanks`() {
        val handle = store.createSession(Date())
        val line1 = LiveTranscriptLine(0L, 1_000L, "first line", final = true)
        val line2 = LiveTranscriptLine(1_000L, 2_500L, "second line", final = true)
        store.transcriptFile(handle.dir).writeText(
            LiveTranscriptWriter.encodeLine(line1) + "\n\n" + LiveTranscriptWriter.encodeLine(line2) + "\n",
        )

        val lines = store.readTranscriptLines(handle.sessionId)

        assertEquals(listOf(line1, line2), lines)
    }

    @Test
    fun `readTranscriptLines returns empty list when no transcript file exists`() {
        val handle = store.createSession(Date())

        assertEquals(emptyList<LiveTranscriptLine>(), store.readTranscriptLines(handle.sessionId))
    }

    @Test
    fun `readTranscriptLines skips mode event lines interleaved with transcript lines`() {
        val handle = store.createSession(Date())
        val line1 = LiveTranscriptLine(0L, 1_000L, "first line", final = true)
        val line2 = LiveTranscriptLine(1_000L, 2_500L, "second line", final = true)
        store.transcriptFile(handle.dir).writeText(
            ModeEventWriter.encodeLine(ModeChange(0L, RecordingMode.LISTEN)) + "\n" +
                LiveTranscriptWriter.encodeLine(line1) + "\n" +
                ModeEventWriter.encodeLine(ModeChange(5_000L, RecordingMode.LISTEN)) + "\n" +
                LiveTranscriptWriter.encodeLine(line2) + "\n",
        )

        val lines = store.readTranscriptLines(handle.sessionId)

        assertEquals(listOf(line1, line2), lines)
    }

    @Test
    fun `writeMeta defaults modes to the initial Listen entry when not provided`() {
        val handle = store.createSession(Date())
        store.oggFile(handle.dir).writeText("ogg")

        store.writeMeta(handle, durationMs = 1_000L, deviceModel = "d", appVersion = "v")

        val meta = store.readMeta(handle.sessionId)
        assertEquals(listOf(SessionModeEntry(0L, "listen")), meta?.modes)
    }

    @Test
    fun `writeMeta round-trips an explicit mode history`() {
        val handle = store.createSession(Date())
        store.oggFile(handle.dir).writeText("ogg")
        val modes = listOf(SessionModeEntry(0L, "listen"), SessionModeEntry(12_000L, "listen"))

        store.writeMeta(handle, durationMs = 20_000L, deviceModel = "d", appVersion = "v", modes = modes)

        val meta = store.readMeta(handle.sessionId)
        assertEquals(modes, meta?.modes)
    }
}
