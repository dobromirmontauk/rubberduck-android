package com.montauk.voicecapture.vault

import android.util.Log
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Talks to the vault repo's GitHub Contents API for the two things
 * [VaultSessionReader] needs (bead vn-edu.54), pulled out as an interface --
 * mirroring [com.montauk.voicecapture.tags.TagTreeSource]'s split of "talk to
 * the network" from "decide what that means" -- so the reader's refresh
 * policy is testable against a fake source with no real HTTP involved.
 */
interface VaultSessionSource {
    /** Never throws: blank token, network error, non-200, or a missing `sessions/` dir all return null. */
    suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>?

    /** Never throws: same failure modes as [listSessionIds] return null. */
    suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray?
}

/**
 * Reuses the same GitHub Contents API call shape
 * [com.montauk.voicecapture.tags.GitHubTagTreeSource] and
 * [com.montauk.voicecapture.upload.GitHubBundleUploader] already use --
 * authenticated GETs against `/repos/{owner}/{repo}/contents/{path}`.
 * [listSessionIds] hits the directory form of that endpoint (`contents/sessions`),
 * which returns a JSON array of entries rather than a single file's base64
 * body; a session is "integrated" iff it has a `dir` entry there, per the
 * organize skill's contract that `sessions/<id>/organization.md` only exists
 * once organization has actually run.
 */
class GitHubVaultSessionSource(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val apiBaseUrl: String = "https://api.github.com",
) : VaultSessionSource {

    override suspend fun listSessionIds(token: String, owner: String, repo: String): Set<String>? {
        if (token.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching { listSessionIdsInternal(token, owner, repo) }
                .onFailure { e -> Log.w(TAG, "listSessionIds($owner/$repo) failed: ${e.message}") }
                .getOrNull()
        }
    }

    private fun listSessionIdsInternal(token: String, owner: String, repo: String): Set<String>? {
        val request = apiRequest(token, "/repos/$owner/$repo/contents/sessions")
        httpClient.newCall(request).execute().use { response ->
            // A vault with no sessions/ dir yet (nothing organized ever) is a
            // valid, common state -- not a fetch failure to fall back from.
            if (response.code == 404) return emptySet()
            if (!response.isSuccessful) {
                Log.w(TAG, "listSessionIds($owner/$repo): HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val entries = json.decodeFromString(ListSerializer(VaultContentsEntry.serializer()), body)
            return entries.filter { it.type == "dir" }.map { it.name }.toSet()
        }
    }

    override suspend fun fetchArtifact(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? {
        if (token.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching { fetchArtifactInternal(token, owner, repo, sessionId, filename) }
                .onFailure { e -> Log.w(TAG, "fetchArtifact($owner/$repo/sessions/$sessionId/$filename) failed: ${e.message}") }
                .getOrNull()
        }
    }

    private fun fetchArtifactInternal(token: String, owner: String, repo: String, sessionId: String, filename: String): ByteArray? {
        val request = apiRequest(token, "/repos/$owner/$repo/contents/sessions/$sessionId/$filename")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "fetchArtifact(sessions/$sessionId/$filename): HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val contents = json.decodeFromString(VaultFileContentsResponse.serializer(), body)
            if (!contents.encoding.equals("base64", ignoreCase = true)) return null
            return Base64.getMimeDecoder().decode(contents.content.replace("\n", ""))
        }
    }

    private fun apiRequest(token: String, path: String): Request = Request.Builder()
        .url("$apiBaseUrl$path")
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/vnd.github+json")
        .build()

    private companion object {
        const val TAG = "GitHubVaultSessionSource"
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class VaultContentsEntry(val name: String = "", val type: String = "")

@Serializable
internal data class VaultFileContentsResponse(val content: String = "", val encoding: String = "")
