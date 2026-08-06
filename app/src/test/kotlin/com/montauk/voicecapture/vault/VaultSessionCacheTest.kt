package com.montauk.voicecapture.vault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Plain-file disk cache for [VaultSessionReader] (bead vn-edu.54), mirroring
 * [com.montauk.voicecapture.tags.TagTreeCacheTest]'s coverage of
 * [com.montauk.voicecapture.tags.TagTreeCache].
 */
class VaultSessionCacheTest {

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = File.createTempFile("vault-session-cache-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @Test
    fun `load returns null when nothing has ever been saved`() {
        val cache = VaultSessionCache(cacheDir)

        assertNull(cache.load())
    }

    @Test
    fun `save then load round-trips the ids and the fetch timestamp`() {
        val cache = VaultSessionCache(cacheDir)
        cache.save(setOf("2026-08-01_0900_ab12", "2026-08-02_1000_cd34"), fetchedAtMs = 12_345L)

        val loaded = cache.load()

        assertEquals(setOf("2026-08-01_0900_ab12", "2026-08-02_1000_cd34"), loaded?.sessionIds)
        assertEquals(12_345L, loaded?.fetchedAtMs)
    }

    @Test
    fun `save then load round-trips an empty ids set -- distinct from never having saved at all`() {
        val cache = VaultSessionCache(cacheDir)
        cache.save(emptySet(), fetchedAtMs = 1L)

        val loaded = cache.load()

        assertEquals(emptySet<String>(), loaded?.sessionIds)
    }

    @Test
    fun `a corrupt meta file degrades to null rather than throwing`() {
        val cache = VaultSessionCache(cacheDir)
        cache.save(setOf("s1"), fetchedAtMs = 1L)
        File(cacheDir, "vault-sessions.meta").writeText("not-a-number")

        assertNull(cache.load())
    }

    @Test
    fun `a later save overwrites the previous ids and timestamp`() {
        val cache = VaultSessionCache(cacheDir)
        cache.save(setOf("s1"), fetchedAtMs = 1L)
        cache.save(setOf("s2"), fetchedAtMs = 2L)

        val loaded = cache.load()

        assertEquals(setOf("s2"), loaded?.sessionIds)
        assertEquals(2L, loaded?.fetchedAtMs)
    }

    @Test
    fun `loadArtifact returns null when nothing has been cached for that session id and filename`() {
        val cache = VaultSessionCache(cacheDir)

        assertNull(cache.loadArtifact("s1", "organization.md"))
    }

    @Test
    fun `saveArtifact then loadArtifact round-trips the raw bytes`() {
        val cache = VaultSessionCache(cacheDir)
        val bytes = "# Organized\n\nSome notes.".toByteArray()
        cache.saveArtifact("s1", "organization.md", bytes)

        val loaded = cache.loadArtifact("s1", "organization.md")

        assertEquals("# Organized\n\nSome notes.", loaded?.toString(Charsets.UTF_8))
    }

    @Test
    fun `artifacts for different session ids never collide even with the same filename`() {
        val cache = VaultSessionCache(cacheDir)
        cache.saveArtifact("s1", "organization.md", "s1's notes".toByteArray())
        cache.saveArtifact("s2", "organization.md", "s2's notes".toByteArray())

        assertEquals("s1's notes", cache.loadArtifact("s1", "organization.md")?.toString(Charsets.UTF_8))
        assertEquals("s2's notes", cache.loadArtifact("s2", "organization.md")?.toString(Charsets.UTF_8))
    }
}
