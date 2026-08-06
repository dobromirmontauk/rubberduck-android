package com.montauk.voicecapture.tags

import java.io.File

/** A previously-fetched `tags.yaml` body plus the wall-clock time it was fetched at. */
data class CachedTagTree(val rawYaml: String, val fetchedAtMs: Long)

/**
 * Plain-file disk cache for the vault's `tags.yaml`, so a session started
 * offline (or with the effective GitHub token currently blank -- signed out,
 * or a transient auth hiccup) still gets tree-anchored tags instead of
 * snapping back to free-form the moment the network or token disappears
 * (bead vn-edu.47: "offline/keyless -> last cache"). Pure `java.io.File`, no
 * Android dependency -- [TagTreeRepository] owns the refresh policy that
 * decides *when* to read/write this; this class only knows how.
 *
 * Two files rather than one so [rawYaml] (arbitrarily large vault content)
 * and its fetch timestamp can be read/written independently without a
 * hand-rolled serialization format for the pair.
 */
class TagTreeCache(private val cacheDir: File) {
    private val yamlFile = File(cacheDir, "tags-tree.yaml")
    private val metaFile = File(cacheDir, "tags-tree.meta")

    /** Null if no cache exists yet, or if either file is present but unreadable/corrupt -- never throws. */
    fun load(): CachedTagTree? {
        if (!yamlFile.isFile || !metaFile.isFile) return null
        return runCatching {
            val fetchedAtMs = metaFile.readText().trim().toLong()
            CachedTagTree(yamlFile.readText(), fetchedAtMs)
        }.getOrNull()
    }

    fun save(rawYaml: String, fetchedAtMs: Long) {
        cacheDir.mkdirs()
        yamlFile.writeText(rawYaml)
        metaFile.writeText(fetchedAtMs.toString())
    }
}
