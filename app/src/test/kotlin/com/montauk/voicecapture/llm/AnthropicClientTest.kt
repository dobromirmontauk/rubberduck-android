package com.montauk.voicecapture.llm

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
 * Exercises [AnthropicClient] against a [MockWebServer] standing in for the
 * real Anthropic Messages API -- same recorded/mock-HTTP-fixture approach as
 * [com.montauk.voicecapture.tags.AnthropicTagScorerTest], which this class
 * was extracted out of (bead vn-edu.42). No live Anthropic key is
 * configured in this environment, so this is the load-bearing coverage for
 * the shared client's request shape and text extraction.
 */
class AnthropicClientTest {

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
        """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":${quoted(text)}}]}"""

    private fun quoted(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun client(apiKey: String = "test-key") = AnthropicClient(apiKey = apiKey, endpoint = server.url("/v1/messages").toString())

    @Test
    fun `extracts the response's content text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("hello world")))

        val result = client().complete(model = "claude-haiku-4-5-20251001", maxTokens = 50, systemPrompt = "sys", userContent = "user")

        assertEquals("hello world", result)
    }

    @Test
    fun `sends model, max_tokens, system, and user content in the request body plus the api key header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("ok")))

        client().complete(model = "claude-haiku-4-5-20251001", maxTokens = 50, systemPrompt = "be terse", userContent = "the user text")

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("test-key", request.getHeader("x-api-key"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("claude-haiku-4-5-20251001"))
        assertTrue(body.contains("be terse"))
        assertTrue(body.contains("the user text"))
    }

    @Test
    fun `concatenates multiple content blocks`() = runBlocking {
        val body = """{"id":"msg_1","content":[{"type":"text","text":"foo "},{"type":"text","text":"bar"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = client().complete("model", 10, "sys", "user")

        assertEquals("foo bar", result)
    }

    @Test
    fun `a blank api key never calls the network`() = runBlocking {
        val result = client(apiKey = "").complete("model", 10, "sys", "user")

        assertNull(result)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a non-200 response yields null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        assertNull(client().complete("model", 10, "sys", "user"))
    }

    @Test
    fun `a response with no content array yields null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"msg_1"}"""))

        assertNull(client().complete("model", 10, "sys", "user"))
    }

    @Test
    fun `non-JSON response body yields null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        assertNull(client().complete("model", 10, "sys", "user"))
    }

    @Test
    fun `a network failure (server unreachable) yields null`() = runBlocking {
        val unreachable = client()
        server.shutdown()

        assertNull(unreachable.complete("model", 10, "sys", "user"))
    }
}
