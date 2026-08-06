package com.montauk.voicecapture.testutil

import com.montauk.voicecapture.vault.VaultSessionSource

/**
 * Deterministic, no-network stand-in for [com.montauk.voicecapture.vault.GitHubVaultSessionSource]
 * (bead vn-edu.54) -- shared by Robolectric tests that need
 * `VoiceCaptureApp.vaultSessionReader` to never touch the real network,
 * whether or not the test happens to set a (fake) non-blank GitHub token.
 * [integratedSessionIds] defaults to empty (nothing integrated); pass a
 * non-empty set to simulate a vault listing hit for those ids.
 *
 * [artifacts] (bead vn-edu.57, Filed-under tab) is a
 * `"<sessionId>/<filename>"`-keyed map of fake artifact bytes -- e.g.
 * `"2026-08-05_0005_guem/organization.json" to fixtureBytes`. A lookup miss
 * returns null, same as [com.montauk.voicecapture.vault.GitHubVaultSessionSource]'s
 * "doesn't exist or fetch failed" contract.
 */
class FakeVaultSessionSource(
    private val integratedSessionIds: Set<String> = emptySet(),
    private val artifacts: Map<String, ByteArray> = emptyMap(),
) : VaultSessionSource {
    override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? = integratedSessionIds
    override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? =
        artifacts["$sessionId/$filename"]
}
