package com.montauk.voicecapture.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the GitHub surfaces the login screen and setup wizard talk
 * to: OAuth device flow (`github.com/login/...`), the authenticated-user API,
 * and the repo-listing API (`api.github.com`). Mirrors the style of
 * `upload/GitHubApiModels.kt` -- only fields actually read are modelled,
 * `ignoreUnknownKeys` so GitHub can add fields freely.
 */
internal val authJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresInSeconds: Int = 0,
    val interval: Int = 5,
)

@Serializable
internal data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
internal data class GitHubUserResponse(
    val login: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
internal data class GitHubRepoResponse(
    val name: String = "",
    @SerialName("full_name") val fullName: String = "",
    val owner: GitHubRepoOwner = GitHubRepoOwner(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
internal data class GitHubRepoOwner(val login: String = "")

@Serializable
internal data class ContentsExistsResponse(val name: String = "", val type: String = "")
