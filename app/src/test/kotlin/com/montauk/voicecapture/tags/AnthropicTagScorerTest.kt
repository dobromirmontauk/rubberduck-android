package com.montauk.voicecapture.tags

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
 * Exercises [AnthropicTagScorer] against a [MockWebServer] standing in for
 * the real Anthropic Messages API, per the recorded/mock-HTTP-fixture
 * approach used elsewhere in this test suite ([com.montauk.voicecapture.upload]
 * has an equivalent for GitHub) -- no live Anthropic key is configured in
 * this environment, so this is the load-bearing coverage for the scorer's
 * request shape and response parsing until one is.
 */
class AnthropicTagScorerTest {

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

    private class RecordingFallback(private val result: List<TagCandidate>) : TagScorer {
        override val minIntervalMs: Long = 0L
        var callCount = 0
        override suspend fun score(transcriptTail: String, currentCandidates: List<String>): List<TagCandidate> {
            callCount++
            return result
        }
    }

    private fun messageResponseBody(text: String): String =
        """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":${quoted(text)}}]}"""

    /** Minimal JSON string-escaping sufficient for these tests' ASCII fixtures. */
    private fun quoted(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun scorer(fallback: TagScorer = RecordingFallback(listOf(TagCandidate("fallback", 0.5)))) =
        AnthropicTagScorer(
            apiKey = "test-key",
            fallback = fallback,
            endpoint = server.url("/v1/messages").toString(),
        )

    @Test
    fun `parses a well-formed tags response into TagCandidates in order`() = runBlocking {
        val body = messageResponseBody("""{"tags":[{"tag":"marathon training","confidence":0.9},{"tag":"nutrition","confidence":0.6}]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val fallback = RecordingFallback(listOf(TagCandidate("should not be used", 1.0)))

        val result = scorer(fallback).score("some transcript tail", emptyList())

        assertEquals(listOf(TagCandidate("marathon training", 0.9), TagCandidate("nutrition", 0.6)), result)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `sends the api key header and the transcript tail in the request body`() = runBlocking {
        val body = messageResponseBody("""{"tags":[]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        scorer().score("we talked about the kitchen remodel", listOf("kitchen remodel"))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("test-key", request.getHeader("x-api-key"))
        val sentBody = request.body.readUtf8()
        assertTrue(sentBody.contains("kitchen remodel"))
        assertTrue(sentBody.contains("claude-haiku"))
    }

    @Test
    fun `strips a markdown json fence before parsing`() = runBlocking {
        val fenced = "```json\n{\"tags\":[{\"tag\":\"budget\",\"confidence\":0.8}]}\n```"
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody(fenced)))

        val result = scorer().score("tail", emptyList())

        assertEquals(listOf(TagCandidate("budget", 0.8)), result)
    }

    @Test
    fun `an empty tags array is a valid answer, not a failure -- no fallback`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("""{"tags":[]}""")))
        val fallback = RecordingFallback(listOf(TagCandidate("should not be used", 1.0)))

        val result = scorer(fallback).score("tail", emptyList())

        assertTrue(result.isEmpty())
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `entries missing required fields are skipped, valid siblings still returned`() = runBlocking {
        val malformedMixed = """{"tags":[{"tag":"good one","confidence":0.7},{"confidence":0.9},{"tag":"","confidence":0.5}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody(malformedMixed)))
        val fallback = RecordingFallback(listOf(TagCandidate("should not be used", 1.0)))

        val result = scorer(fallback).score("tail", emptyList())

        assertEquals(listOf(TagCandidate("good one", 0.7)), result)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `non-JSON model output degrades silently to the fallback scorer`() = runBlocking {
        val prose = "I think the main topics here are running and nutrition, roughly."
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody(prose)))
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))

        val result = scorer(fallback).score("tail", emptyList())

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a tags array where every entry is malformed degrades to the fallback`() = runBlocking {
        val allMalformed = """{"tags":[{"confidence":0.9},{"tag":"   ","confidence":0.5}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody(allMalformed)))
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))

        val result = scorer(fallback).score("tail", emptyList())

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a non-200 response degrades silently to the fallback scorer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))

        val result = scorer(fallback).score("tail", emptyList())

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a network failure (server unreachable) degrades silently to the fallback scorer`() = runBlocking {
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))
        val unreachable = scorer(fallback)
        server.shutdown() // guarantees the connection attempt fails

        val result = unreachable.score("tail", emptyList())

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a blank api key never calls the network -- goes straight to the fallback`() = runBlocking {
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))
        val keyless = AnthropicTagScorer(apiKey = "", fallback = fallback, endpoint = server.url("/v1/messages").toString())

        val result = keyless.score("tail", emptyList())

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
        assertNull("expected no HTTP request at all", server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `out-of-range confidence from the model is coerced into 0 to 1`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("""{"tags":[{"tag":"topic","confidence":4.2}]}""")))

        val result = scorer().score("tail", emptyList())

        assertEquals(1.0, result.single().confidence, 0.0001)
    }
}
