package com.montauk.voicecapture.tags

/**
 * Owns the fetch-vs-cache refresh policy for the vault's `tags.yaml` (bead
 * vn-edu.47): "fetch on connect + refresh at most daily; cached locally;
 * offline/keyless -> last cache; no vault -> free-form fallback as today."
 *
 * One rule, [currentTree], covers all four cases without a special-cased
 * branch per case -- each falls out of the same due-check:
 *  - **No vault ever configured** (no [TagTreeCache] entry yet, and either
 *    [token] is blank or the fetch itself fails): nothing to fall back to,
 *    so the result is [TagTree.EMPTY] -- today's free-form behavior.
 *  - **First use after connecting** (no cache yet, [token] present): the
 *    cache miss alone makes a refresh due, so this fetches immediately --
 *    "on connect."
 *  - **Cache present and fresh** (fetched within [refreshIntervalMs]): no
 *    network call at all, parses the cached body -- "at most daily."
 *  - **Cache present and stale, online**: refetches and overwrites the
 *    cache -- "daily refresh."
 *  - **Cache present and stale (or no cache attempt possible), but the
 *    fetch didn't happen or failed** (blank [token] i.e. keylessly signed
 *    out, or a real network failure while offline): falls through to
 *    whatever's on disk, however stale -- "offline/keyless -> last cache."
 *    Only when there's *also* no cache does this bottom out at
 *    [TagTree.EMPTY].
 */
class TagTreeRepository(
    private val cache: TagTreeCache,
    private val source: TagTreeSource = GitHubTagTreeSource(),
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun currentTree(token: String, owner: String, repo: String): TagTree {
        val cached = cache.load()
        val due = cached == null || (clock() - cached.fetchedAtMs) >= refreshIntervalMs

        if (due && token.isNotBlank()) {
            val fetched = source.fetchRaw(token, owner, repo)
            if (fetched != null) {
                val now = clock()
                cache.save(fetched, now)
                return TagTreeParser.parse(fetched)
            }
        }

        return cached?.let { TagTreeParser.parse(it.rawYaml) } ?: TagTree.EMPTY
    }

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000 // one day
    }
}
