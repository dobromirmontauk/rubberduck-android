package com.montauk.voicecapture.tags

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Plain-file disk cache, pure `java.io.File` -- no Android/Robolectric needed. */
class TagTreeCacheTest {

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = File.createTempFile("tag-tree-cache-test", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @Test
    fun `load returns null when nothing has ever been saved`() {
        val cache = TagTreeCache(cacheDir)

        assertNull(cache.load())
    }

    @Test
    fun `save then load round-trips the raw yaml and fetch timestamp exactly`() {
        val cache = TagTreeCache(cacheDir)

        cache.save("- id: t_a\n  name: alpha\n", fetchedAtMs = 123_456L)
        val loaded = cache.load()

        assertEquals(CachedTagTree("- id: t_a\n  name: alpha\n", 123_456L), loaded)
    }

    @Test
    fun `a second save overwrites the first, not appends`() {
        val cache = TagTreeCache(cacheDir)
        cache.save("first version", fetchedAtMs = 1L)

        cache.save("second version", fetchedAtMs = 2L)

        assertEquals(CachedTagTree("second version", 2L), cache.load())
    }

    @Test
    fun `save creates the cache directory if it does not exist yet`() {
        val freshDir = File(cacheDir, "nested/does-not-exist-yet")
        val cache = TagTreeCache(freshDir)

        cache.save("content", fetchedAtMs = 1L)

        assertEquals(CachedTagTree("content", 1L), cache.load())
    }

    @Test
    fun `a metadata file with corrupt (non-numeric) content is treated as no cache, not a crash`() {
        val cache = TagTreeCache(cacheDir)
        cache.save("content", fetchedAtMs = 1L)
        File(cacheDir, "tags-tree.meta").writeText("not-a-number")

        assertNull(cache.load())
    }

    @Test
    fun `only the yaml file present (no meta file yet) is treated as no cache`() {
        cacheDir.mkdirs()
        File(cacheDir, "tags-tree.yaml").writeText("- id: t_a\n  name: alpha\n")
        val cache = TagTreeCache(cacheDir)

        assertNull(cache.load())
    }
}
