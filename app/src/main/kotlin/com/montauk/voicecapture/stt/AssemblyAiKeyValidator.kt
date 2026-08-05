package com.montauk.voicecapture.stt

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class AssemblyAiKeyError : Exception() {
    data object Invalid : AssemblyAiKeyError()
    data class NetworkUnavailable(override val cause: Throwable?) : AssemblyAiKeyError()
    data class Other(override val message: String) : AssemblyAiKeyError()
}

/**
 * Test-connection check for the setup wizard's transcription step: a cheap
 * authenticated GET (list transcripts, capped at 1) against AssemblyAI's v2
 * API, whose only purpose here is to confirm the key is accepted before it's
 * stored -- same shape as [com.montauk.voicecapture.auth.GitHubAccountClient.validateToken]
 * for the GitHub side.
 */
class AssemblyAiKeyValidator(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://api.assemblyai.com/v2",
) {
    suspend fun validate(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/transcript?limit=1")
                .addHeader("authorization", key)
                .build()
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> Unit
                    401, 403 -> throw AssemblyAiKeyError.Invalid
                    else -> throw AssemblyAiKeyError.Other("unexpected status ${response.code}")
                }
            }
        }.recoverCatching { e -> if (e is AssemblyAiKeyError) throw e else throw AssemblyAiKeyError.NetworkUnavailable(e) }
    }
}
