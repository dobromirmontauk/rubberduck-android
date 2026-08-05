package com.montauk.voicecapture.auth

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request

/** GitHub identity as shown by the login and wizard account step. */
data class GitHubIdentity(val login: String, val avatarUrl: String?)

/** One row in the setup wizard's vault picker (step 2). */
data class GitHubRepoOption(val fullName: String, val owner: String, val name: String)

sealed class GitHubAccountError : Exception() {
    data object InvalidToken : GitHubAccountError()
    data class NetworkUnavailable(override val cause: Throwable?) : GitHubAccountError()
    data class Other(override val message: String) : GitHubAccountError()
}

/**
 * Talks to `api.github.com`'s user + repos surfaces on behalf of the login
 * screen ("Use an access token" validates via `GET /user`) and the setup
 * wizard's vault-picker step ("Choose your vault" lists `GET /user/repos`,
 * then checks each candidate for `docs/ingest-contract.md` via the contents
 * API). Deliberately separate from `upload/GitHubBundleUploader.kt`, which
 * talks to the Git Data + LFS APIs for the already-selected vault -- this
 * client is about *discovering and validating* the account/vault before that
 * point, not writing to it.
 */
class GitHubAccountClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val apiBaseUrl: String = "https://api.github.com",
) {
    suspend fun validateToken(token: String): Result<GitHubIdentity> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authedRequest(token, "/user").build()
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val user = authJson.decodeFromString(
                            GitHubUserResponse.serializer(),
                            response.body?.string().orEmpty(),
                        )
                        GitHubIdentity(login = user.login, avatarUrl = user.avatarUrl)
                    }
                    401, 403 -> throw GitHubAccountError.InvalidToken
                    else -> throw GitHubAccountError.Other("unexpected status ${response.code} validating token")
                }
            }
        }.recoverCatching { e -> if (e is GitHubAccountError) throw e else throw GitHubAccountError.NetworkUnavailable(e) }
    }

    /** Sorted by most-recently-updated, matching the wizard's vault-picker order. */
    suspend fun listRepos(token: String, perPage: Int = 50): Result<List<GitHubRepoOption>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authedRequest(token, "/user/repos?sort=updated&per_page=$perPage").build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) throw GitHubAccountError.InvalidToken
                    if (!response.isSuccessful) throw GitHubAccountError.Other("unexpected status ${response.code} listing repos")
                    val body = response.body?.string().orEmpty()
                    val repos = authJson.decodeFromString(
                        ListSerializer(GitHubRepoResponse.serializer()),
                        body,
                    )
                    repos.map { GitHubRepoOption(fullName = it.fullName, owner = it.owner.login, name = it.name) }
                }
            }.recoverCatching { e -> if (e is GitHubAccountError) throw e else throw GitHubAccountError.NetworkUnavailable(e) }
        }

    /**
     * Silent per-repo validation for the vault picker: a candidate vault is
     * one whose default branch contains `docs/ingest-contract.md` -- the
     * file the capture app and the organize pipeline both anchor on. 404
     * means "not a vault" (still selectable, just dimmed with a warning in
     * the UI), not an error.
     */
    suspend fun hasIngestContract(token: String, owner: String, repo: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authedRequest(token, "/repos/$owner/$repo/contents/docs/ingest-contract.md").build()
                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> true
                        404 -> false
                        401, 403 -> throw GitHubAccountError.InvalidToken
                        else -> throw GitHubAccountError.Other("unexpected status ${response.code} checking $owner/$repo")
                    }
                }
            }.recoverCatching { e -> if (e is GitHubAccountError) throw e else throw GitHubAccountError.NetworkUnavailable(e) }
        }

    private fun authedRequest(token: String, path: String): Request.Builder =
        Request.Builder()
            .url("$apiBaseUrl$path")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
}
