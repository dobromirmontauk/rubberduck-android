package com.montauk.voicecapture.tags

import android.util.Log
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the current `tags.yaml` body from a vault repo. Pulled out as an
 * interface (bead vn-edu.47, mirroring [com.montauk.voicecapture.llm.AnthropicClient]'s
 * separation of "talk to the network" from "decide what that means") so
 * [TagTreeRepository]'s refresh policy is testable against a fake source with
 * no real HTTP involved.
 */
interface TagTreeSource {
    /** Never throws: any failure (blank token, network error, non-200, missing file, bad base64) returns null. */
    suspend fun fetchRaw(token: String, owner: String, repo: String): String?
}

/**
 * Reuses the exact same GitHub Contents API call shape [com.montauk.voicecapture.upload.GitHubBundleUploader]
 * already uses to peek at `.gitattributes` -- a single authenticated GET
 * against `/repos/{owner}/{repo}/contents/{path}`, decoding the `content`
 * field's base64 body. `tags.yaml` is small (see the vault's own header
 * comment: "intentionally shallow... expected to grow organically"), so no
 * pagination or streaming concerns here, unlike the bundle uploader's audio
 * blobs.
 */
class GitHubTagTreeSource(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val apiBaseUrl: String = "https://api.github.com",
    private val path: String = "tags.yaml",
) : TagTreeSource {

    override suspend fun fetchRaw(token: String, owner: String, repo: String): String? {
        if (token.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching { fetchInternal(token, owner, repo) }
                .onFailure { e -> Log.w(TAG, "fetchRaw($owner/$repo/$path) failed: ${e.message}") }
                .getOrNull()
        }
    }

    private fun fetchInternal(token: String, owner: String, repo: String): String? {
        val request = Request.Builder()
            .url("$apiBaseUrl/repos/$owner/$repo/contents/$path")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "fetchRaw($owner/$repo/$path): HTTP ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val contents = json.decodeFromString(TagTreeContentsResponse.serializer(), body)
            if (!contents.encoding.equals("base64", ignoreCase = true)) return null
            return String(Base64.getMimeDecoder().decode(contents.content.replace("\n", "")), Charsets.UTF_8)
        }
    }

    private companion object {
        const val TAG = "GitHubTagTreeSource"
        val json = Json { ignoreUnknownKeys = true }
    }
}

@kotlinx.serialization.Serializable
internal data class TagTreeContentsResponse(val content: String = "", val encoding: String = "")
