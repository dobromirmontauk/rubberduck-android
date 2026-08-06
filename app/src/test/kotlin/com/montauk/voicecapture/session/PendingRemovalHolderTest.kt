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
 * Bead vn-edu.67, extended by asn-638: pure schedule/undo/flush/flushAll
 * semantics of [PendingRemovalHolder] -- no Robolectric, no coroutines/
 * virtual time, since the holder itself owns none of the 10s countdown's
 * timing (that lives in `SessionListScreen`'s `PendingSessionRow`, see its
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
        PendingRemovalHolder.flushAll()
    }

    @After
    fun tearDown() {
        PendingRemovalHolder.flushAll()
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

        assertTrue("schedule() must never itself delete -- only flush()/flushAll() may", dir.exists())
        assertEquals("a", PendingRemovalHolder.state.value["a"]?.sessionId)
    }

    @Test
    fun `flush commits the pending removal for that sessionId only`() {
        seed("a")
        val dir = store.sessionDir("a")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })

        PendingRemovalHolder.flush("a")

        assertFalse("flush() must run the deferred execute", dir.exists())
        assertNull("state clears once flushed", PendingRemovalHolder.state.value["a"])
    }

    @Test
    fun `flush with nothing pending for that sessionId is a harmless no-op`() {
        PendingRemovalHolder.flush("a")

        assertTrue(PendingRemovalHolder.state.value.isEmpty())
    }

    @Test
    fun `undo clears the pending removal without ever calling execute`() {
        seed("a")
        val dir = store.sessionDir("a")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })

        PendingRemovalHolder.undo("a")

        assertTrue("undo must leave the file system untouched", dir.exists())
        assertNull(PendingRemovalHolder.state.value["a"])
    }

    @Test
    fun `undo followed by a later flush call still never deletes -- undo fully clears the pending removal, not just its visible state`() {
        seed("a")
        val dir = store.sessionDir("a")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })
        PendingRemovalHolder.undo("a")

        PendingRemovalHolder.flush("a")

        assertTrue(dir.exists())
    }

    @Test
    fun `multiple rows can be pending at once, each on its own independent clock`() {
        seed("a")
        seed("b")
        val dirA = store.sessionDir("a")
        val dirB = store.sessionDir("b")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })

        PendingRemovalHolder.schedule(PendingRemoval("b", RemovalAction.ARCHIVE, "Archived"), execute = { store.deleteSession("b") })

        assertTrue("scheduling a second removal must NOT flush the first -- asn-638 drops vn-edu.67's one-at-a-time behavior", dirA.exists())
        assertTrue(dirB.exists())
        assertEquals(setOf("a", "b"), PendingRemovalHolder.state.value.keys)
    }

    @Test
    fun `undo of one concurrently-pending row leaves the other's countdown and execute untouched`() {
        seed("a")
        seed("b")
        val dirA = store.sessionDir("a")
        val dirB = store.sessionDir("b")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })
        PendingRemovalHolder.schedule(PendingRemoval("b", RemovalAction.ARCHIVE, "Archived"), execute = { store.deleteSession("b") })

        PendingRemovalHolder.undo("a")

        assertTrue("undone row's file must be untouched", dirA.exists())
        assertNull(PendingRemovalHolder.state.value["a"])
        assertEquals("b's own pending entry must survive a's undo", "b", PendingRemovalHolder.state.value["b"]?.sessionId)
        assertTrue("b must still be deferred, not yet flushed", dirB.exists())
    }

    @Test
    fun `flush of one concurrently-pending row leaves the other's countdown and execute untouched`() {
        seed("a")
        seed("b")
        val dirA = store.sessionDir("a")
        val dirB = store.sessionDir("b")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })
        PendingRemovalHolder.schedule(PendingRemoval("b", RemovalAction.ARCHIVE, "Archived"), execute = { store.deleteSession("b") })

        PendingRemovalHolder.flush("a")

        assertFalse("a's execute must have run", dirA.exists())
        assertTrue("b must be untouched by a's flush", dirB.exists())
        assertEquals("b", PendingRemovalHolder.state.value["b"]?.sessionId)
    }

    @Test
    fun `flushAll commits every pending removal`() {
        seed("a")
        seed("b")
        val dirA = store.sessionDir("a")
        val dirB = store.sessionDir("b")
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { store.deleteSession("a") })
        PendingRemovalHolder.schedule(PendingRemoval("b", RemovalAction.ARCHIVE, "Archived"), execute = { store.deleteSession("b") })

        PendingRemovalHolder.flushAll()

        assertFalse("flushAll() must run every deferred execute -- no lost commits on backgrounding/screen exit", dirA.exists())
        assertFalse(dirB.exists())
        assertTrue(PendingRemovalHolder.state.value.isEmpty())
    }

    @Test
    fun `flushAll with nothing pending is a harmless no-op`() {
        PendingRemovalHolder.flushAll()

        assertTrue(PendingRemovalHolder.state.value.isEmpty())
    }

    @Test
    fun `re-scheduling the same sessionId before its clock elapses replaces the pending removal without ever running the earlier execute`() {
        seed("a")
        val dir = store.sessionDir("a")
        var firstExecuteRan = false
        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.DELETE, "Deleted"), execute = { firstExecuteRan = true })

        PendingRemovalHolder.schedule(PendingRemoval("a", RemovalAction.ARCHIVE, "Archived"), execute = { store.deleteSession("a") })

        assertEquals("the later schedule's own removal record must be what's published", "Archived", PendingRemovalHolder.state.value["a"]?.message)
        PendingRemovalHolder.flush("a")

        assertFalse("the earlier (discarded) execute must never run", firstExecuteRan)
        assertFalse("the later (replacing) execute must run", dir.exists())
    }
}
