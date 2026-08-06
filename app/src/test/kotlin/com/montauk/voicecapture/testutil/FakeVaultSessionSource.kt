package com.montauk.voicecapture.testutil

import com.montauk.voicecapture.vault.VaultSessionSource

/**
 * Deterministic, no-network stand-in for [com.montauk.voicecapture.vault.GitHubVaultSessionSource]
 * (bead vn-edu.54) -- shared by Robolectric tests that need
 * `VoiceCaptureApp.vaultSessionReader` to never touch the real network,
 * whether or not the test happens to set a (fake) non-blank GitHub token.
 * [integratedSessionIds] defaults to empty (nothing integrated); pass a
 * non-empty set to simulate a vault listing hit for those ids.
 */
class FakeVaultSessionSource(private val integratedSessionIds: Set<String> = emptySet()) : VaultSessionSource {
    override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? = integratedSessionIds
    override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? = null
}
