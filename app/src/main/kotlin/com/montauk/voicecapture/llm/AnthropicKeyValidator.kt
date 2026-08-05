package com.montauk.voicecapture.llm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class AnthropicKeyError : Exception() {
    data object Invalid : AnthropicKeyError()
    data class NetworkUnavailable(override val cause: Throwable?) : AnthropicKeyError()
    data class Other(override val message: String) : AnthropicKeyError()
}

/**
 * Test-connection check for the setup wizard's Intelligence step and
 * Settings' Replace flow (bead vn-edu.48): a cheap authenticated GET against
 * Anthropic's models-list endpoint, whose only purpose here is to confirm
 * the key is accepted before it's stored -- no completion tokens spent, same
 * shape as [com.montauk.voicecapture.stt.AssemblyAiKeyValidator] for the
 * AssemblyAI side and [com.montauk.voicecapture.auth.GitHubAccountClient.validateToken]
 * for GitHub.
 */
class AnthropicKeyValidator(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://api.anthropic.com/v1",
) {
    suspend fun validate(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/models?limit=1")
                .addHeader("x-api-key", key)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .build()
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> Unit
                    401, 403 -> throw AnthropicKeyError.Invalid
                    else -> throw AnthropicKeyError.Other("unexpected status ${response.code}")
                }
            }
        }.recoverCatching { e -> if (e is AnthropicKeyError) throw e else throw AnthropicKeyError.NetworkUnavailable(e) }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
