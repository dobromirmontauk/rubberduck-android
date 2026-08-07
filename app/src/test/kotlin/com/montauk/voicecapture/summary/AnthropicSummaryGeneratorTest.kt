package com.montauk.voicecapture.summary

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises [AnthropicSummaryGenerator] against a [MockWebServer] standing
 * in for the real Anthropic Messages API, same recorded/mock-HTTP-fixture
 * approach as [com.montauk.voicecapture.tags.AnthropicTagScorerTest] -- no
 * live Anthropic key is configured in this environment.
 */
class AnthropicSummaryGeneratorTest {

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

    // Explicit SummaryGenerator return type (not the concrete
    // AnthropicSummaryGenerator) so every 2-arg `generate(...)` call below
    // still compiles via the interface's own discardedBullets default --
    // Kotlin forbids an *overriding* function from redeclaring a default
    // parameter value, so that default is only visible when dispatch is
    // through the interface-typed reference.
    private fun generator(apiKey: String = "test-key"): SummaryGenerator =
        AnthropicSummaryGenerator(apiKey = apiKey, endpoint = server.url("/v1/messages").toString())

    @Test
    fun `parses a well-formed bullets response in order`() = runBlocking {
        val body = messageResponseBody("""{"bullets":["first bullet","second bullet"]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = generator().generate("full transcript text", emptyList())

        assertEquals(listOf("first bullet", "second bullet"), result)
    }

    @Test
    fun `strips a markdown json fence Claude sometimes adds`() = runBlocking {
        val body = messageResponseBody("```json\n{\"bullets\":[\"a bullet\"]}\n```")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = generator().generate("full transcript text", emptyList())

        assertEquals(listOf("a bullet"), result)
    }

    @Test
    fun `sends the system prompt and both content blocks with cache_control on the cacheable ones`() = runBlocking {
        val body = messageResponseBody("""{"bullets":[]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        generator().generate("the full transcript so far", listOf("existing bullet"))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val requestBody = request.body.readUtf8()
        assertTrue("system must be a content-block array, not a plain string", requestBody.contains("\"system\":["))
        assertTrue(requestBody.contains("the full transcript so far"))
        assertTrue(requestBody.contains("existing bullet"))
        // Two cache_control breakpoints: the system prompt block and the transcript block.
        assertEquals(2, Regex("cache_control").findAll(requestBody).count())
    }

    @Test
    fun `previous bullets are included verbatim in the user content`() = runBlocking {
        val body = messageResponseBody("""{"bullets":["existing bullet","new bullet"]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        generator().generate("transcript", listOf("existing bullet"))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.body.readUtf8().contains("existing bullet"))
    }

    @Test
    fun `an empty bullets array from a proposal-free transcript parses to an empty list, not a failure`() = runBlocking {
        val body = messageResponseBody("""{"bullets":[]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = generator().generate("transcript", emptyList())

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `a non-200 response yields null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        assertNull(generator().generate("transcript", emptyList()))
    }

    @Test
    fun `unparseable json yields null, not a crash`() = runBlocking {
        val body = messageResponseBody("not json at all")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        assertNull(generator().generate("transcript", emptyList()))
    }

    @Test
    fun `a blank api key never calls the network`() = runBlocking {
        val result = generator(apiKey = "").generate("transcript", emptyList())

        assertNull(result)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `discarded bullets are included in the prompt, told not to be regenerated`() = runBlocking {
        val body = messageResponseBody("""{"bullets":["existing bullet"]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        generator().generate("transcript", listOf("existing bullet"), discardedBullets = listOf("a note the user swiped away"))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(request.body.readUtf8().contains("a note the user swiped away"))
    }

    @Test
    fun `an empty discarded-bullets list adds no extra content block`() = runBlocking {
        val body = messageResponseBody("""{"bullets":[]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        generator().generate("transcript", emptyList(), discardedBullets = emptyList())

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertFalse(request.body.readUtf8().contains("discarded"))
    }
}
