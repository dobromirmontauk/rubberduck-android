package com.montauk.voicecapture.auth

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** One step of the device-flow poll loop, before [DeviceFlowPollStateMachine] classifies it. */
internal sealed class DevicePollOutcome {
    data class Success(val accessToken: String) : DevicePollOutcome()
    data object Pending : DevicePollOutcome()
    data object SlowDown : DevicePollOutcome()
    data object ExpiredToken : DevicePollOutcome()
    data object AccessDenied : DevicePollOutcome()
    data class Error(val message: String) : DevicePollOutcome()
}

/** What the login screen renders at any point during the device flow. */
sealed class DeviceFlowPhase {
    data object Idle : DeviceFlowPhase()
    data class AwaitingUser(val userCode: String, val verificationUri: String) : DeviceFlowPhase()
    data class Success(val accessToken: String) : DeviceFlowPhase()
    data object Expired : DeviceFlowPhase()
    data object Denied : DeviceFlowPhase()
    data class Error(val message: String) : DeviceFlowPhase()
}

/**
 * Pure state machine driving the device-flow poll loop: given the previous
 * poll's outcome, decides the next [DeviceFlowPhase] and (for `slow_down`)
 * grows the poll interval per the OAuth device-flow spec, which requires
 * backing off by 5s rather than retrying at the same cadence. No I/O, no
 * coroutines -- kept separate from [GitHubDeviceFlowClient] so the state
 * transitions are testable without a mock server.
 */
class DeviceFlowPollStateMachine(initialIntervalSeconds: Int) {
    var intervalSeconds: Int = initialIntervalSeconds
        private set

    internal fun apply(outcome: DevicePollOutcome): DeviceFlowPhase = when (outcome) {
        is DevicePollOutcome.Success -> DeviceFlowPhase.Success(outcome.accessToken)
        DevicePollOutcome.Pending -> DeviceFlowPhase.Idle
        DevicePollOutcome.SlowDown -> {
            intervalSeconds += SLOW_DOWN_STEP_SECONDS
            DeviceFlowPhase.Idle
        }
        DevicePollOutcome.ExpiredToken -> DeviceFlowPhase.Expired
        DevicePollOutcome.AccessDenied -> DeviceFlowPhase.Denied
        is DevicePollOutcome.Error -> DeviceFlowPhase.Error(outcome.message)
    }

    private companion object {
        const val SLOW_DOWN_STEP_SECONDS = 5
    }
}

/**
 * GitHub OAuth device flow (RFC 8628): request a device/user code pair, then
 * poll for the resulting access token. Talks to `github.com` directly (not
 * `api.github.com` -- these two endpoints live on the web host), synchronous
 * OkHttp calls hopped onto [Dispatchers.IO], matching the rest of the
 * codebase's HTTP client style (see `upload/GitHubBundleUploader.kt`).
 *
 * [clientId] is compiled in from `BuildConfig.GITHUB_OAUTH_CLIENT_ID`, which
 * defaults to a placeholder when `github.oauthClientId` isn't set in
 * `local.properties` -- see the comment at that buildConfigField in
 * `app/build.gradle.kts`. Every call here is a real HTTP request regardless
 * of whether a real client id is configured; GitHub simply rejects the
 * placeholder with a normal device-flow error, which [runDeviceFlow] surfaces
 * like any other failure.
 */
class GitHubDeviceFlowClient(
    private val clientId: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://github.com",
) {
    internal suspend fun requestDeviceCode(scope: String = "repo"): Result<DeviceCodeResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("scope", scope)
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/login/device/code")
                    .addHeader("Accept", "application/json")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error("device code request failed: HTTP ${response.code}")
                    }
                    authJson.decodeFromString(DeviceCodeResponse.serializer(), responseBody)
                }
            }
        }

    internal suspend fun pollOnce(deviceCode: String): DevicePollOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val request = Request.Builder()
                .url("$baseUrl/login/oauth/access_token")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext DevicePollOutcome.Error("HTTP ${response.code}")
                }
                val parsed = authJson.decodeFromString(AccessTokenResponse.serializer(), responseBody)
                classify(parsed)
            }
        }.getOrElse { e -> DevicePollOutcome.Error(e.message ?: "network error") }
    }

    private fun classify(response: AccessTokenResponse): DevicePollOutcome = when {
        !response.accessToken.isNullOrBlank() -> DevicePollOutcome.Success(response.accessToken)
        response.error == "authorization_pending" -> DevicePollOutcome.Pending
        response.error == "slow_down" -> DevicePollOutcome.SlowDown
        response.error == "expired_token" -> DevicePollOutcome.ExpiredToken
        response.error == "access_denied" -> DevicePollOutcome.AccessDenied
        response.error != null -> DevicePollOutcome.Error(response.errorDescription ?: response.error)
        else -> DevicePollOutcome.Error("unrecognized response")
    }

    /**
     * Drives the full flow end to end: request the code, hand [onPhase] the
     * user_code + verification URL to render, then poll until success,
     * denial, expiry, or the device code's own `expires_in` deadline.
     * `delay`s between polls on the calling coroutine -- callers launch this
     * from a screen-scoped coroutine scope so cancelling (leaving the screen)
     * stops the loop for free.
     */
    suspend fun runDeviceFlow(scope: String = "repo", onPhase: suspend (DeviceFlowPhase) -> Unit) {
        val codeResult = requestDeviceCode(scope)
        codeResult.fold(
            onSuccess = { info ->
                onPhase(DeviceFlowPhase.AwaitingUser(info.userCode, info.verificationUri))
                val machine = DeviceFlowPollStateMachine(info.interval.coerceAtLeast(1))
                val deadline = System.currentTimeMillis() + info.expiresInSeconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(machine.intervalSeconds * 1000L)
                    val outcome = pollOnce(info.deviceCode)
                    val phase = machine.apply(outcome)
                    if (phase !is DeviceFlowPhase.Idle) {
                        onPhase(phase)
                        return
                    }
                }
                onPhase(DeviceFlowPhase.Expired)
            },
            onFailure = { e -> onPhase(DeviceFlowPhase.Error(e.message ?: "device code request failed")) },
        )
    }
}
