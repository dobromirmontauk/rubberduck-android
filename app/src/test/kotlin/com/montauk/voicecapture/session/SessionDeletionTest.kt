package com.montauk.voicecapture.session

import com.montauk.voicecapture.testutil.SessionFixtures
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bead vn-edu.55: pure-logic + on-disk coverage for "delete from phone" and
 * "archive all integrated sessions" -- no Robolectric/Compose needed since
 * [DeleteConfirmPolicy], [BulkArchiveEligibility], and [SessionStore.deleteSession]
 * are all plain JVM code. UI-level gating/interaction is covered separately
 * under `ui/SessionDeleteInteractionTest.kt`.
 */
class SessionDeletionTest {

    // ---- DeleteConfirmPolicy: which confirm variant a session needs ----

    @Test
    fun `LOCAL sessions require the hard warning`() {
        assertTrue(DeleteConfirmPolicy.requiresHardWarning(UploadState.LOCAL))
    }

    @Test
    fun `QUEUED sessions require the hard warning`() {
        assertTrue(DeleteConfirmPolicy.requiresHardWarning(UploadState.QUEUED))
    }

    @Test
    fun `UPLOADED sessions do not require the hard warning`() {
        assertFalse(DeleteConfirmPolicy.requiresHardWarning(UploadState.UPLOADED))
    }

    // ---- BulkArchiveEligibility: which sessions the bulk action may touch ----

    private fun summary(sessionId: String, uploadState: UploadState) =
        SessionSummary(sessionId, title = sessionId, startedAt = SessionFixtures.FIXED_STARTED_AT, durationMs = 1_000L, uploadState = uploadState)

    @Test
    fun `eligibleSessionIds includes only UPLOADED sessions the vault listing hits`() {
        val sessions = listOf(
            summary("a", UploadState.UPLOADED),
            summary("b", UploadState.UPLOADED),
            summary("c", UploadState.LOCAL),
            summary("d", UploadState.QUEUED),
        )

        val eligible = BulkArchiveEligibility.eligibleSessionIds(sessions, integratedSessionIds = setOf("a"))

        assertEquals(listOf("a"), eligible)
    }

    @Test
    fun `an UPLOADED session the vault listing does not hit is excluded -- uploaded is not the same as integrated`() {
        val sessions = listOf(summary("a", UploadState.UPLOADED))

        val eligible = BulkArchiveEligibility.eligibleSessionIds(sessions, integratedSessionIds = emptySet())

        assertTrue(eligible.isEmpty())
    }

    @Test
    fun `LOCAL and QUEUED sessions are never eligible even if their id happens to appear in integratedSessionIds`() {
        // Shouldn't happen in practice (a LOCAL/QUEUED session was never uploaded, so
        // the vault can't know its id) but SessionStatusResolver only promotes to
        // INTEGRATED from UPLOADED -- pin that this holds even for a coincidental id match.
        val sessions = listOf(summary("a", UploadState.LOCAL), summary("b", UploadState.QUEUED))

        val eligible = BulkArchiveEligibility.eligibleSessionIds(sessions, integratedSessionIds = setOf("a", "b"))

        assertTrue(eligible.isEmpty())
    }

    // ---- SessionStore.deleteSession: the actual on-disk removal ----

    private lateinit var baseDir: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("session-deletion-test").toFile()
        store = SessionStore(baseDir)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun `deleteSession removes the entire session directory`() {
        SessionFixtures.seedSession(store, "2026-08-01_0900_ab12", "Kitchen remodel budget check")
        val dir = store.sessionDir("2026-08-01_0900_ab12")
        assertTrue(dir.exists())

        val result = store.deleteSession("2026-08-01_0900_ab12")

        assertTrue(result)
        assertFalse("session directory must be gone entirely", dir.exists())
    }

    @Test
    fun `deleteSession on an already-missing session id is a harmless no-op`() {
        val result = store.deleteSession("2026-08-01_0900_never-existed")

        assertTrue(result)
    }

    @Test
    fun `deleteSession removes the session from listSessions`() {
        SessionFixtures.seedSession(store, "2026-08-01_0900_ab12", "Kitchen remodel budget check")

        store.deleteSession("2026-08-01_0900_ab12")

        assertTrue(store.listSessions().none { it.sessionId == "2026-08-01_0900_ab12" })
    }

    // ---- End-to-end: bulk archive only ever deletes eligible sessions ----

    @Test
    fun `bulk archive deletes only INTEGRATED sessions, leaving LOCAL, QUEUED, and merely-UPLOADED ones on disk`() {
        SessionFixtures.seedSession(store, "integrated-1", "Kitchen remodel budget check", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(store, "integrated-2", "Marathon training recap", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(store, "uploaded-not-integrated", "Deck repair notes", uploadState = UploadState.UPLOADED)
        SessionFixtures.seedSession(store, "local-only", "Dog walk download", uploadState = UploadState.LOCAL)
        SessionFixtures.seedSession(store, "queued-only", "Drive home hiring", uploadState = UploadState.QUEUED)

        val integratedSessionIds = setOf("integrated-1", "integrated-2")
        val eligible = BulkArchiveEligibility.eligibleSessionIds(store.listSessions(), integratedSessionIds)
        eligible.forEach { sessionId -> store.deleteSession(sessionId) }

        assertEquals(setOf("integrated-1", "integrated-2"), eligible.toSet())
        assertFalse(store.sessionDir("integrated-1").exists())
        assertFalse(store.sessionDir("integrated-2").exists())
        assertTrue("an UPLOADED-but-not-integrated session must survive a bulk archive", store.sessionDir("uploaded-not-integrated").exists())
        assertTrue("a LOCAL session must survive a bulk archive", store.sessionDir("local-only").exists())
        assertTrue("a QUEUED session must survive a bulk archive", store.sessionDir("queued-only").exists())
    }
}
