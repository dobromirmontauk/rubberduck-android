package com.montauk.voicecapture.stt

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wizard step 3's test-connection check, exercised against a mock AssemblyAI. */
class AssemblyAiKeyValidatorTest {

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

    private fun validator(): AssemblyAiKeyValidator =
        AssemblyAiKeyValidator(baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `200 means the key is accepted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"transcripts": []}"""))

        val result = validator().validate("fake-key")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertTrue(recorded.getHeader("authorization") == "fake-key")
    }

    @Test
    fun `401 surfaces as an Invalid key, not a generic failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = validator().validate("bad-key")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AssemblyAiKeyError.Invalid)
    }
}
