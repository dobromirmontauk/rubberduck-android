package com.montauk.voicecapture.vault

import java.io.File

/** A previously-fetched vault `sessions/` directory listing plus the wall-clock time it was fetched at. */
data class CachedVaultSessions(val sessionIds: Set<String>, val fetchedAtMs: Long)

/**
 * Plain-file disk cache backing [VaultSessionReader], mirroring
 * [com.montauk.voicecapture.tags.TagTreeCache]'s content+meta split so the
 * ids listing and its fetch timestamp can be read/written independently.
 * Pure `java.io.File`, no Android dependency -- [VaultSessionReader] owns the
 * refresh policy that decides *when* to read/write this; this class only
 * knows how.
 *
 * Also caches individual vault artifact bytes fetched via
 * [VaultSessionSource.fetchArtifact] (bead vn-edu.57's Filed-under tab),
 * keyed by session id + filename under a separate `artifacts/` subdirectory
 * so they don't collide with the ids listing.
 */
class VaultSessionCache(private val cacheDir: File) {
    private val idsFile = File(cacheDir, "vault-sessions.ids")
    private val metaFile = File(cacheDir, "vault-sessions.meta")
    private val artifactsDir = File(cacheDir, "artifacts")

    /** Null if no cache exists yet, or if either file is present but unreadable/corrupt -- never throws. */
    fun load(): CachedVaultSessions? {
        if (!idsFile.isFile || !metaFile.isFile) return null
        return runCatching {
            val fetchedAtMs = metaFile.readText().trim().toLong()
            val ids = idsFile.readLines().filter { it.isNotBlank() }.toSet()
            CachedVaultSessions(ids, fetchedAtMs)
        }.getOrNull()
    }

    fun save(sessionIds: Set<String>, fetchedAtMs: Long) {
        cacheDir.mkdirs()
        idsFile.writeText(sessionIds.joinToString("\n"))
        metaFile.writeText(fetchedAtMs.toString())
    }

    /** Null if this exact (sessionId, filename) pair was never cached, or is unreadable -- never throws. */
    fun loadArtifact(sessionId: String, filename: String): ByteArray? {
        val file = artifactFile(sessionId, filename)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun saveArtifact(sessionId: String, filename: String, bytes: ByteArray) {
        val file = artifactFile(sessionId, filename)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    // Session ids are this app's own generated `yyyy-MM-dd_HHmm_xxxx` format
    // and vault artifact filenames are fixed strings like "organization.md" --
    // both filesystem-safe as-is, no escaping needed.
    private fun artifactFile(sessionId: String, filename: String): File = File(File(artifactsDir, sessionId), filename)
}
