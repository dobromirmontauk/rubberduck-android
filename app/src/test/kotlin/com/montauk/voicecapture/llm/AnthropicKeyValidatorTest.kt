package com.montauk.voicecapture.llm

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wizard Intelligence step + Settings Replace flow's test-connection check, exercised against a mock Anthropic. */
class AnthropicKeyValidatorTest {

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

    private fun validator(): AnthropicKeyValidator =
        AnthropicKeyValidator(baseUrl = server.url("/v1").toString().trimEnd('/'))

    @Test
    fun `200 means the key is accepted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))

        val result = validator().validate("fake-key")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertTrue(recorded.getHeader("x-api-key") == "fake-key")
        assertTrue(recorded.getHeader("anthropic-version") == "2023-06-01")
    }

    @Test
    fun `401 surfaces as an Invalid key, not a generic failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = validator().validate("bad-key")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AnthropicKeyError.Invalid)
    }

    @Test
    fun `network failure surfaces as NetworkUnavailable`() = runTest {
        server.shutdown()

        val result = validator().validate("fake-key")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AnthropicKeyError.NetworkUnavailable)
    }
}
