package com.montauk.voicecapture.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises [GitHubDeviceFlowClient] against a mock HTTP server standing in
 * for `github.com`'s device-flow endpoints -- the classification in
 * [GitHubDeviceFlowClient.pollOnce] is what feeds [DeviceFlowPollStateMachine],
 * so every outcome it can produce is covered here.
 */
class GitHubDeviceFlowClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): GitHubDeviceFlowClient =
        GitHubDeviceFlowClient(clientId = "fake-client-id", baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `requestDeviceCode parses the code, user code, and verification URL`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"device_code":"dc123","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""",
            ),
        )

        val result = client().requestDeviceCode()

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("dc123", info.deviceCode)
        assertEquals("WDJB-MJHT", info.userCode)
        assertEquals("https://github.com/login/device", info.verificationUri)
        assertEquals(5, info.interval)
    }

    @Test
    fun `requestDeviceCode surfaces a non-2xx as a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client().requestDeviceCode()

        assertTrue(result.isFailure)
    }

    @Test
    fun `pollOnce classifies authorization_pending`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error":"authorization_pending"}"""))

        val outcome = client().pollOnce("dc123")

        assertEquals(DevicePollOutcome.Pending, outcome)
    }

    @Test
    fun `pollOnce classifies slow_down`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error":"slow_down"}"""))

        assertEquals(DevicePollOutcome.SlowDown, client().pollOnce("dc123"))
    }

    @Test
    fun `pollOnce classifies expired_token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error":"expired_token"}"""))

        assertEquals(DevicePollOutcome.ExpiredToken, client().pollOnce("dc123"))
    }

    @Test
    fun `pollOnce classifies access_denied`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error":"access_denied"}"""))

        assertEquals(DevicePollOutcome.AccessDenied, client().pollOnce("dc123"))
    }

    @Test
    fun `pollOnce classifies a successful token grant`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"gho_realtoken","token_type":"bearer","scope":"repo"}"""),
        )

        val outcome = client().pollOnce("dc123")

        assertTrue(outcome is DevicePollOutcome.Success)
        assertEquals("gho_realtoken", (outcome as DevicePollOutcome.Success).accessToken)
    }

    @Test
    fun `pollOnce classifies an HTTP-level failure as Error, not a false Pending`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val outcome = client().pollOnce("dc123")

        assertTrue(outcome is DevicePollOutcome.Error)
    }
}
