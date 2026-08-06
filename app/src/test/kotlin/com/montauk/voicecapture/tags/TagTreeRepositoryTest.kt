package com.montauk.voicecapture.tags

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bead vn-edu.47's refresh policy: "fetch on connect + refresh at most
 * daily; cached locally; offline/keyless -> last cache; no vault -> free-
 * form fallback as today." [FakeTagTreeSource] stands in for the real
 * GitHub call so every case here is a pure function of (cache state, fetch
 * outcome, clock, token) with no real network involved.
 */
class TagTreeRepositoryTest {

    private lateinit var cacheDir: File

    private class FakeTagTreeSource(private val result: String?) : TagTreeSource {
        var callCount = 0
        var lastToken: String? = null
        var lastOwner: String? = null
        var lastRepo: String? = null

        override suspend fun fetchRaw(token: String, owner: String, repo: String): String? {
            callCount++
            lastToken = token
            lastOwner = owner
            lastRepo = repo
            return result
        }
    }

    @Before
    fun setUp() {
        cacheDir = File.createTempFile("tag-tree-repo-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    private fun oneNodeYaml(name: String) = "- id: t_a\n  name: $name\n  parent: null\n  description: d.\n"

    @Test
    fun `no vault ever configured -- blank token and no cache -- resolves to TagTree EMPTY without fetching`() = runBlocking {
        val source = FakeTagTreeSource(result = oneNodeYaml("should-not-be-fetched"))
        val repo = TagTreeRepository(TagTreeCache(cacheDir), source)

        val tree = repo.currentTree(token = "", owner = "o", repo = "r")

        assertTrue(tree.isEmpty)
        assertEquals(0, source.callCount)
    }

    @Test
    fun `first use after connecting -- no cache yet, token present -- fetches immediately`() = runBlocking {
        val source = FakeTagTreeSource(result = oneNodeYaml("kitchen-remodel"))
        val repo = TagTreeRepository(TagTreeCache(cacheDir), source)

        val tree = repo.currentTree(token = "tok", owner = "o", repo = "r")

        assertEquals(1, source.callCount)
        assertEquals(listOf("kitchen-remodel"), tree.nodes.map { it.name })
        assertEquals("tok", source.lastToken)
        assertEquals("o", source.lastOwner)
        assertEquals("r", source.lastRepo)
    }

    @Test
    fun `a successful fetch is written to the cache so a later call within the refresh window skips the network`() = runBlocking {
        val source = FakeTagTreeSource(result = oneNodeYaml("first-fetch"))
        var now = 1_000L
        val repo = TagTreeRepository(TagTreeCache(cacheDir), source, clock = { now })
        repo.currentTree(token = "tok", owner = "o", repo = "r")

        now += 1_000L // well within the daily refresh window
        val tree = repo.currentTree(token = "tok", owner = "o", repo = "r")

        assertEquals(1, source.callCount) // no second fetch
        assertEquals(listOf("first-fetch"), tree.nodes.map { it.name })
    }

    @Test
    fun `daily refresh -- cache older than the refresh interval, online -- refetches and returns the fresh tree`() = runBlocking {
        val source = FakeTagTreeSource(result = oneNodeYaml("stale"))
        var now = 0L
        val repo = TagTreeRepository(TagTreeCache(cacheDir), source, refreshIntervalMs = 1_000L, clock = { now })
        repo.currentTree(token = "tok", owner = "o", repo = "r")

        now = 5_000L // past the 1s refresh interval

        val tree = repo.currentTree(token = "tok", owner = "o", repo = "r")

        assertEquals(2, source.callCount) // proves it actually refetched, not just reused the cache
        assertEquals(listOf("stale"), tree.nodes.map { it.name })
    }

    @Test
    fun `offline -- cache exists but a fetch attempt fails -- falls back to the last cached tree`() = runBlocking {
        val onlineSource = FakeTagTreeSource(result = oneNodeYaml("cached-topic"))
        val cache = TagTreeCache(cacheDir)
        var now = 0L
        TagTreeRepository(cache, onlineSource, clock = { now }).currentTree(token = "tok", owner = "o", repo = "r")

        now = TagTreeRepository.DEFAULT_REFRESH_INTERVAL_MS + 1 // force a due refresh
        val offlineSource = FakeTagTreeSource(result = null) // network failure
        val repo = TagTreeRepository(cache, offlineSource, clock = { now })

        val tree = repo.currentTree(token = "tok", owner = "o", repo = "r")

        assertEquals(1, offlineSource.callCount) // it did try
        assertEquals(listOf("cached-topic"), tree.nodes.map { it.name }) // but fell back to the stale cache
    }

    @Test
    fun `keyless -- token now blank but a cache exists from before -- still uses the last cache, not free-form`() = runBlocking {
        val cache = TagTreeCache(cacheDir)
        var now = 0L
        val onlineSource = FakeTagTreeSource(result = oneNodeYaml("cached-topic"))
        TagTreeRepository(cache, onlineSource, clock = { now }).currentTree(token = "tok", owner = "o", repo = "r")

        now += 1_000L // well within the refresh window, but token has since gone blank (signed out)
        val neverCalled = FakeTagTreeSource(result = oneNodeYaml("should-not-be-fetched"))
        val repo = TagTreeRepository(cache, neverCalled, clock = { now })

        val tree = repo.currentTree(token = "", owner = "o", repo = "r")

        assertEquals(0, neverCalled.callCount) // blank token never attempts a fetch
        assertEquals(listOf("cached-topic"), tree.nodes.map { it.name })
    }

    @Test
    fun `no cache and a fetch failure (or blank token) both bottom out at TagTree EMPTY, never a crash`() = runBlocking {
        val source = FakeTagTreeSource(result = null)
        val repo = TagTreeRepository(TagTreeCache(cacheDir), source)

        val tree = repo.currentTree(token = "tok", owner = "o", repo = "r")

        assertTrue(tree.isEmpty)
    }

    @Test
    fun `a cached body with no parseable entries resolves to an empty tree, never a throw`() = runBlocking {
        val cache = TagTreeCache(cacheDir)
        cache.save("not valid yaml at all, no dash-prefixed entries anywhere", fetchedAtMs = System.currentTimeMillis())
        val repo = TagTreeRepository(cache, FakeTagTreeSource(result = null))

        val tree = repo.currentTree(token = "", owner = "o", repo = "r")

        assertTrue(tree.isEmpty)
    }
}
