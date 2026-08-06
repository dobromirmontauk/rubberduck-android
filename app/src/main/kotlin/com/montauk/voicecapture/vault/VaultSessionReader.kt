package com.montauk.voicecapture.vault

/**
 * Snapshot of the vault's organized-session state as of one [VaultSessionReader.refresh]
 * call (bead vn-edu.54). [asOfMs] is null only when there's no data at all --
 * keyless/no-vault, or a fetch failure with nothing cached yet. [stale] is
 * true exactly when a live refresh attempt was made but failed (offline,
 * network error, non-200) and this snapshot came from [VaultSessionCache]
 * instead of a fresh fetch.
 */
data class VaultSessionSnapshot(
    val integratedSessionIds: Set<String>,
    val asOfMs: Long?,
    val stale: Boolean,
) {
    companion object {
        val EMPTY = VaultSessionSnapshot(emptySet(), asOfMs = null, stale = false)
    }
}

/**
 * Owns the fetch-vs-cache policy behind the Sessions screen's INTEGRATED
 * status (bead vn-edu.54): an already-UPLOADED session graduates to
 * INTEGRATED once the vault's organize skill has produced
 * `sessions/<id>/organization.md` for it -- see
 * [com.montauk.voicecapture.session.SessionStatusResolver], which turns a
 * [VaultSessionSnapshot] plus a session's own [com.montauk.voicecapture.session.UploadState]
 * into its display [com.montauk.voicecapture.session.SessionStatus].
 *
 * Unlike [com.montauk.voicecapture.tags.TagTreeRepository]'s at-most-daily
 * refresh (a tag tree changes rarely), this refetches on every call: the
 * Sessions screen calls [refresh] once per screen visit (no time-based
 * throttle here), and a session graduating to INTEGRATED is exactly the
 * kind of change a user re-opening the screen wants to see promptly.
 * [fetchSessionArtifact] is unused by this bead's UI but exposed now for
 * vn-edu.57's Canonical/Filed-to tabs to build on.
 */
class VaultSessionReader(
    private val cache: VaultSessionCache,
    private val source: VaultSessionSource = GitHubVaultSessionSource(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Keyless/no-vault (blank [token]) always returns [VaultSessionSnapshot.EMPTY]
     * -- deliberately ignoring any cache left over from a prior sign-in,
     * unlike [com.montauk.voicecapture.tags.TagTreeRepository]'s keyless-uses-
     * last-cache policy. A signed-out user seeing a stale INTEGRATED chip for
     * a vault they're no longer connected to would read as a fabricated
     * status, not a helpful offline fallback -- bead vn-edu.54's acceptance
     * criteria is explicit: "keyless/no-vault builds show Local/Uploaded
     * only... no fake Integrated."
     */
    suspend fun refresh(token: String, owner: String, repo: String): VaultSessionSnapshot {
        if (token.isBlank()) return VaultSessionSnapshot.EMPTY

        val fetched = source.listSessionIds(token, owner, repo)
        if (fetched != null) {
            val now = clock()
            cache.save(fetched, now)
            return VaultSessionSnapshot(fetched, now, stale = false)
        }

        val cached = cache.load() ?: return VaultSessionSnapshot.EMPTY
        return VaultSessionSnapshot(cached.sessionIds, cached.fetchedAtMs, stale = true)
    }

    /**
     * Decoded text of a vault artifact at `sessions/<sessionId>/<filename>`
     * (e.g. `organization.md`), disk-cached by [VaultSessionCache] so a
     * repeat or offline view doesn't need the network. Left for vn-edu.57;
     * unused by this bead's UI.
     */
    suspend fun fetchSessionArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): String? {
        if (token.isNotBlank()) {
            val fetched = source.fetchArtifact(token, owner, repo, sessionId, filename)
            if (fetched != null) {
                cache.saveArtifact(sessionId, filename, fetched)
                return String(fetched, Charsets.UTF_8)
            }
        }
        return cache.loadArtifact(sessionId, filename)?.let { String(it, Charsets.UTF_8) }
    }
}
