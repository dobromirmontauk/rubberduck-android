package com.montauk.voicecapture.auth

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub identity as shown by the login and wizard account step.
 * [isOverScoped] is true when the `/user` response carried an
 * `X-OAuth-Scopes` header -- GitHub sends that header for classic PATs and
 * OAuth App tokens (whatever scopes they were granted), and omits it
 * entirely for fine-grained PATs and GitHub App tokens. Bead vn-edu.30: the
 * app's documented, first-class token is a fine-grained PAT scoped to just
 * the vault repo's Contents permission, so this flag is the one signal
 * available to gently flag "this token can reach more than it needs to"
 * without GitHub exposing the fine-grained permission set itself over the
 * API.
 */
data class GitHubIdentity(val login: String, val avatarUrl: String?, val isOverScoped: Boolean = false)

/** One row in the setup wizard's vault picker (step 2). */
data class GitHubRepoOption(val fullName: String, val owner: String, val name: String)

sealed class GitHubAccountError : Exception() {
    data object InvalidToken : GitHubAccountError()

    /** The token is valid (passed `/user`) but can't reach the specific vault repo it will need to upload to. */
    data class RepoNotAccessible(val owner: String, val repo: String) : GitHubAccountError()
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
                        GitHubIdentity(
                            login = user.login,
                            avatarUrl = user.avatarUrl,
                            isOverScoped = response.header("X-OAuth-Scopes") != null,
                        )
                    }
                    401, 403 -> throw GitHubAccountError.InvalidToken
                    else -> throw GitHubAccountError.Other("unexpected status ${response.code} validating token")
                }
            }
        }.recoverCatching { e -> if (e is GitHubAccountError) throw e else throw GitHubAccountError.NetworkUnavailable(e) }
    }

    /**
     * Confirms [token] can reach [owner]/[repo] specifically -- the repo an
     * upload will actually target -- via `GET /repos/{owner}/{repo}`.
     * Distinct from [validateToken]: a token can be valid (pass `/user`)
     * while still lacking access to *this* repo, e.g. a fine-grained PAT
     * scoped to some other repository. GitHub 404s a private repo the token
     * can't see rather than 403ing it, so 404 here means "not accessible,"
     * not "doesn't exist."
     */
    suspend fun hasRepoAccess(token: String, owner: String, repo: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authedRequest(token, "/repos/$owner/$repo").build()
                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> true
                        404 -> false
                        401, 403 -> throw GitHubAccountError.InvalidToken
                        else -> throw GitHubAccountError.Other("unexpected status ${response.code} checking $owner/$repo access")
                    }
                }
            }.recoverCatching { e -> if (e is GitHubAccountError) throw e else throw GitHubAccountError.NetworkUnavailable(e) }
        }

    /**
     * Combined check backing the login screen's PAT entry (bead vn-edu.30):
     * [validateToken] plus [hasRepoAccess] against the vault repo the app is
     * actually configured to upload to. A token that's valid but can't reach
     * [owner]/[repo] fails here with [GitHubAccountError.RepoNotAccessible]
     * instead of being accepted and only failing later, on the first real
     * upload attempt.
     */
    suspend fun validateForVault(token: String, owner: String, repo: String): Result<GitHubIdentity> =
        runCatching {
            val identity = validateToken(token).getOrThrow()
            val hasAccess = hasRepoAccess(token, owner, repo).getOrThrow()
            if (!hasAccess) throw GitHubAccountError.RepoNotAccessible(owner, repo)
            identity
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
