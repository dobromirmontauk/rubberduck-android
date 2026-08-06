package com.montauk.voicecapture.session

import com.montauk.voicecapture.testutil.SessionFixtures
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bead vn-edu.67: pure schedule/undo/flush semantics of [PendingRemovalHolder]
 * -- no Robolectric, no coroutines/virtual time, since the holder itself
 * owns none of the undo window's timing (that lives in `AppNavHost`, see its
 * KDoc). Wires [PendingRemovalHolder.schedule]'s `execute` to a real
 * [SessionStore.deleteSession] against on-disk fixtures rather than a bare
 * boolean flag, so "no deletion before flush" is proven against the actual
 * artifact this bead cares about (session files), not just a call counter.
 */
class PendingRemovalHolderTest {

    private lateinit var baseDir: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("pending-removal-test").toFile()
        store = SessionStore(baseDir)
        // Belt-and-suspenders: this is a process-global singleton in
        // production, so leave it exactly as found (empty) for the next test
        // regardless of what this one does to it.
        PendingRemovalHolder.flush()
    }

    @After
    fun tearDown() {
        PendingRemovalHolder.flush()
        baseDir.deleteRecursively()
    }

    private fun seed(sessionId: String) {
        SessionFixtures.seedSession(store, sessionId, "fixture", uploadState = UploadState.UPLOADED)
    }

    @Test
    fun `schedule defers execute -- the session directory is still on disk immediately after`() {
        seed("a")
        val dir = store.sessionDir("a")

        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })

        assertTrue("schedule() must never itself delete -- only flush() may", dir.exists())
        assertEquals("a", PendingRemovalHolder.state.value?.sessionId)
    }

    @Test
    fun `flush commits the pending removal`() {
        seed("a")
        val dir = store.sessionDir("a")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })

        PendingRemovalHolder.flush()

        assertFalse("flush() must run the deferred execute", dir.exists())
        assertNull("state clears once flushed", PendingRemovalHolder.state.value)
    }

    @Test
    fun `flush with nothing pending is a harmless no-op`() {
        PendingRemovalHolder.flush()

        assertNull(PendingRemovalHolder.state.value)
    }

    @Test
    fun `undo clears the pending removal without ever calling execute`() {
        seed("a")
        val dir = store.sessionDir("a")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })

        PendingRemovalHolder.undo()

        assertTrue("undo must leave the file system untouched", dir.exists())
        assertNull(PendingRemovalHolder.state.value)
    }

    @Test
    fun `undo followed by a later flush call still never deletes -- undo fully clears the pending removal, not just its visible state`() {
        seed("a")
        val dir = store.sessionDir("a")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })
        PendingRemovalHolder.undo()

        PendingRemovalHolder.flush()

        assertTrue(dir.exists())
    }

    @Test
    fun `scheduling a second removal flushes the first immediately, Gmail-style one-at-a-time`() {
        seed("a")
        seed("b")
        val dirA = store.sessionDir("a")
        val dirB = store.sessionDir("b")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })

        PendingRemovalHolder.schedule(PendingRemoval("b", RemovalAction.ARCHIVE, "Archived"), execute = { store.deleteSession("b") })

        assertFalse("the first pending removal (a) must be committed once a second one is scheduled", dirA.exists())
        assertTrue("the second (b) must still be deferred, not yet flushed", dirB.exists())
        assertEquals("b", PendingRemovalHolder.state.value?.sessionId)
    }
}
