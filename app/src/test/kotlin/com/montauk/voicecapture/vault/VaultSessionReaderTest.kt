package com.montauk.voicecapture.vault

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [VaultSessionReader]'s fetch-vs-cache policy (bead vn-edu.54), exercised
 * against a fake source with no real HTTP involved -- same approach as
 * [com.montauk.voicecapture.tags.TagTreeRepositoryTest]. Unlike that
 * repository's at-most-daily refresh, this reader refetches on every call
 * (the Sessions screen calls [VaultSessionReader.refresh] once per screen
 * visit), and keyless/no-vault deliberately ignores any leftover cache --
 * see [VaultSessionReader.refresh]'s KDoc for why.
 */
class VaultSessionReaderTest {

    private lateinit var cacheDir: File

    private class FakeVaultSessionSource(private val result: Set<String>?) : VaultSessionSource {
        var callCount = 0
        var lastToken: String? = null

        override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? {
            callCount++
            lastToken = token
            return result
        }

        override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? = null
    }

    @Before
    fun setUp() {
        cacheDir = File.createTempFile("vault-session-reader-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @Test
    fun `keyless -- blank token -- resolves to EMPTY without ever calling the source`() = runBlocking {
        val source = FakeVaultSessionSource(result = setOf("should-not-be-fetched"))
        val reader = VaultSessionReader(VaultSessionCache(cacheDir), source)

        val snapshot = reader.refresh(token = "", owner = "o", repo = "r")

        assertEquals(emptySet<String>(), snapshot.integratedSessionIds)
        assertNull(snapshot.asOfMs)
        assertFalse(snapshot.stale)
        assertEquals(0, source.callCount)
    }

    @Test
    fun `a successful fetch returns a fresh, non-stale snapshot and writes the cache`() = runBlocking {
        val source = FakeVaultSessionSource(result = setOf("2026-08-01_0900_ab12"))
        var now = 1_000L
        val reader = VaultSessionReader(VaultSessionCache(cacheDir), source, clock = { now })

        val snapshot = reader.refresh(token = "tok", owner = "o", repo = "r")

        assertEquals(setOf("2026-08-01_0900_ab12"), snapshot.integratedSessionIds)
        assertEquals(1_000L, snapshot.asOfMs)
        assertFalse(snapshot.stale)
        assertEquals("tok", source.lastToken)
    }

    @Test
    fun `every refresh call fetches again -- no daily throttle, unlike TagTreeRepository`() = runBlocking {
        val source = FakeVaultSessionSource(result = setOf("s1"))
        val reader = VaultSessionReader(VaultSessionCache(cacheDir), source)

        reader.refresh(token = "tok", owner = "o", repo = "r")
        reader.refresh(token = "tok", owner = "o", repo = "r")

        assertEquals(2, source.callCount)
    }

    @Test
    fun `offline -- a fetch attempt fails but a prior cache exists -- falls back to the cache and marks it stale`() = runBlocking {
        val cache = VaultSessionCache(cacheDir)
        var now = 0L
        val onlineSource = FakeVaultSessionSource(result = setOf("cached-session"))
        VaultSessionReader(cache, onlineSource, clock = { now }).refresh(token = "tok", owner = "o", repo = "r")

        now = 5_000L
        val offlineSource = FakeVaultSessionSource(result = null)
        val reader = VaultSessionReader(cache, offlineSource, clock = { now })

        val snapshot = reader.refresh(token = "tok", owner = "o", repo = "r")

        assertEquals(1, offlineSource.callCount) // it did try
        assertEquals(setOf("cached-session"), snapshot.integratedSessionIds) // but fell back to the stale cache
        assertEquals(0L, snapshot.asOfMs) // the cache's own fetch time, not `now`
        assertTrue(snapshot.stale)
    }

    @Test
    fun `offline with no prior cache at all resolves to EMPTY, never a crash`() = runBlocking {
        val source = FakeVaultSessionSource(result = null)
        val reader = VaultSessionReader(VaultSessionCache(cacheDir), source)

        val snapshot = reader.refresh(token = "tok", owner = "o", repo = "r")

        assertEquals(emptySet<String>(), snapshot.integratedSessionIds)
        assertNull(snapshot.asOfMs)
        assertFalse(snapshot.stale)
    }

    @Test
    fun `keyless ignores a leftover cache from a prior sign-in -- no fake INTEGRATED after logout`() = runBlocking {
        val cache = VaultSessionCache(cacheDir)
        val onlineSource = FakeVaultSessionSource(result = setOf("cached-session"))
        VaultSessionReader(cache, onlineSource).refresh(token = "tok", owner = "o", repo = "r")

        val neverCalled = FakeVaultSessionSource(result = setOf("should-not-be-fetched"))
        val reader = VaultSessionReader(cache, neverCalled)

        val snapshot = reader.refresh(token = "", owner = "o", repo = "r")

        assertEquals(0, neverCalled.callCount)
        assertEquals(emptySet<String>(), snapshot.integratedSessionIds)
        assertFalse(snapshot.stale)
    }

    @Test
    fun `fetchSessionArtifact prefers a fresh fetch and caches it`() = runBlocking {
        val source = object : VaultSessionSource {
            override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? = null
            override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? =
                "fresh content".toByteArray()
        }
        val cache = VaultSessionCache(cacheDir)
        val reader = VaultSessionReader(cache, source)

        val text = reader.fetchSessionArtifact(token = "tok", owner = "o", repo = "r", sessionId = "s1", filename = "organization.md")

        assertEquals("fresh content", text)
        assertEquals("fresh content", cache.loadArtifact("s1", "organization.md")?.toString(Charsets.UTF_8))
    }

    @Test
    fun `fetchSessionArtifact falls back to the cached artifact when the live fetch fails`() = runBlocking {
        val cache = VaultSessionCache(cacheDir)
        cache.saveArtifact("s1", "organization.md", "stale cached content".toByteArray())
        val failingSource = object : VaultSessionSource {
            override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? = null
            override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? = null
        }
        val reader = VaultSessionReader(cache, failingSource)

        val text = reader.fetchSessionArtifact(token = "tok", owner = "o", repo = "r", sessionId = "s1", filename = "organization.md")

        assertEquals("stale cached content", text)
    }

    @Test
    fun `fetchSessionArtifact with a blank token skips the network and serves the cache`() = runBlocking {
        val cache = VaultSessionCache(cacheDir)
        cache.saveArtifact("s1", "organization.md", "cached content".toByteArray())
        val neverCalled = object : VaultSessionSource {
            var called = false
            override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? = null
            override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? {
                called = true
                return null
            }
        }
        val reader = VaultSessionReader(cache, neverCalled)

        val text = reader.fetchSessionArtifact(token = "", owner = "o", repo = "r", sessionId = "s1", filename = "organization.md")

        assertEquals("cached content", text)
        assertFalse(neverCalled.called)
    }
}
