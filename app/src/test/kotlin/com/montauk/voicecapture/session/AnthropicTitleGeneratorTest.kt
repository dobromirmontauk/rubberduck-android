package com.montauk.voicecapture.session

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises [AnthropicTitleGenerator] end to end (prompt building -> HTTP ->
 * response parsing) against a [MockWebServer], same recorded/mock-HTTP-
 * fixture approach as [com.montauk.voicecapture.tags.AnthropicTagScorerTest]
 * -- no live Anthropic key is configured in this environment, so this is the
 * load-bearing coverage for the bead vn-edu.42 title path until one is.
 */
class AnthropicTitleGeneratorTest {

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

    private fun messageResponseBody(text: String): String =
        """{"id":"msg_1","content":[{"type":"text","text":${quoted(text)}}]}"""

    private fun quoted(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun generator(apiKey: String = "test-key", timeoutMs: Long = 5_000L) =
        AnthropicTitleGenerator(apiKey = apiKey, endpoint = server.url("/v1/messages").toString(), timeoutMs = timeoutMs)

    @Test
    fun `a well-formed response becomes the parsed title`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("Kitchen remodel bids and layout call")))

        val result = generator().generate(listOf("So the budget for the kitchen remodel is", "we need a quote from the electrician"))

        assertEquals("Kitchen remodel bids and layout call", result)
    }

    @Test
    fun `a quoted, punctuated response is cleaned up via TitleResponseParser`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("\"Kitchen remodel bids.\"")))

        val result = generator().generate(listOf("some transcript line"))

        assertEquals("Kitchen remodel bids", result)
    }

    @Test
    fun `sends the transcript excerpt and model in the request body plus the api key header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("A title")))

        generator().generate(listOf("we talked about the kitchen remodel budget"))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("test-key", request.getHeader("x-api-key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("kitchen remodel budget"))
        assertTrue(body.contains("claude-haiku"))
    }

    @Test
    fun `an empty transcript never calls the network and yields null`() = runBlocking {
        val result = generator().generate(emptyList())

        assertNull(result)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a blank api key never calls the network and yields null`() = runBlocking {
        val result = generator(apiKey = "").generate(listOf("some line"))

        assertNull(result)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a non-200 response degrades to null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        assertNull(generator().generate(listOf("some line")))
    }

    @Test
    fun `a network failure (server unreachable) degrades to null`() = runBlocking {
        val unreachable = generator()
        server.shutdown()

        assertNull(unreachable.generate(listOf("some line")))
    }

    @Test
    fun `a response slower than the timeout degrades to null rather than waiting`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(messageResponseBody("A title")).setBodyDelay(2, TimeUnit.SECONDS),
        )

        // Short timeout so this test doesn't actually wait out a real 5s default.
        val result = generator(timeoutMs = 100L).generate(listOf("some line"))

        assertNull(result)
    }

    @Test
    fun `a long transcript is trimmed to head+tail before being sent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("A title")))
        val lines = (1..40).map { "line $it" }

        generator().generate(lines)

        val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue("expected an early line", body.contains("line 1\\n"))
        assertTrue("expected an elision marker", body.contains("[...]"))
        assertTrue("expected a late line", body.contains("line 40"))
        assertTrue("expected a middle line to be dropped", !body.contains("line 20\\n"))
    }
}
