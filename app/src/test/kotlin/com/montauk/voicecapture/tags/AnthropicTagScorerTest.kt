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
        override suspend fun score(transcriptTail: String, currentCandidates: List<String>, tree: TagTree): List<TagCandidate> {
            callCount++
            return result
        }
    }

    /** Mirrors a slice of voice-vault's real tags.yaml (work/mashgin, home/kitchen-remodel) -- see the bead's fixture. */
    private fun fixtureTree(): TagTree = TagTree(
        listOf(
            TagTreeNode("t_work", "work", null, "Professional life in general."),
            TagTreeNode("t_mashgin", "mashgin", "t_work", "Mashgin-specific work -- the job, not the industry."),
            TagTreeNode("t_home", "home", null, "House projects, maintenance, household logistics."),
            TagTreeNode("t_kitchen", "kitchen-remodel", "t_home", "The kitchen remodel project -- contractors, bids, timeline, decisions."),
        ),
    )

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

        val result = scorer(fallback).score("some transcript tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("marathon training", 0.9), TagCandidate("nutrition", 0.6)), result)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `sends the api key header and the transcript tail in the request body`() = runBlocking {
        val body = messageResponseBody("""{"tags":[]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        scorer().score("we talked about the kitchen remodel", listOf("kitchen remodel"), TagTree.EMPTY)

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

        val result = scorer().score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("budget", 0.8)), result)
    }

    @Test
    fun `an empty tags array is a valid answer, not a failure -- no fallback`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("""{"tags":[]}""")))
        val fallback = RecordingFallback(listOf(TagCandidate("should not be used", 1.0)))

        val result = scorer(fallback).score("tail", emptyList(), TagTree.EMPTY)

        assertTrue(result.isEmpty())
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `entries missing required fields are skipped, valid siblings still returned`() = runBlocking {
        val malformedMixed = """{"tags":[{"tag":"good one","confidence":0.7},{"confidence":0.9},{"tag":"","confidence":0.5}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody(malformedMixed)))
        val fallback = RecordingFallback(listOf(TagCandidate("should not be used", 1.0)))

        val result = scorer(fallback).score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("good one", 0.7)), result)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `non-JSON model output degrades silently to the fallback scorer`() = runBlocking {
        val prose = "I think the main topics here are running and nutrition, roughly."
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody(prose)))
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))

        val result = scorer(fallback).score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a tags array where every entry is malformed degrades to the fallback`() = runBlocking {
        val allMalformed = """{"tags":[{"confidence":0.9},{"tag":"   ","confidence":0.5}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody(allMalformed)))
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))

        val result = scorer(fallback).score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a non-200 response degrades silently to the fallback scorer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))

        val result = scorer(fallback).score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a network failure (server unreachable) degrades silently to the fallback scorer`() = runBlocking {
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))
        val unreachable = scorer(fallback)
        server.shutdown() // guarantees the connection attempt fails

        val result = unreachable.score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `a blank api key never calls the network -- goes straight to the fallback`() = runBlocking {
        val fallback = RecordingFallback(listOf(TagCandidate("heuristic result", 0.4)))
        val keyless = AnthropicTagScorer(apiKey = "", fallback = fallback, endpoint = server.url("/v1/messages").toString())

        val result = keyless.score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("heuristic result", 0.4)), result)
        assertEquals(1, fallback.callCount)
        assertNull("expected no HTTP request at all", server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `out-of-range confidence from the model is coerced into 0 to 1`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("""{"tags":[{"tag":"topic","confidence":4.2}]}""")))

        val result = scorer().score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(1.0, result.single().confidence, 0.0001)
    }

    // --- Bead vn-edu.47: tree-anchored scoring ---

    @Test
    fun `a non-empty tree is included in the request body, keyed by id and full path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("""{"tags":[]}""")))

        scorer().score("we're deciding on cabinet colors", emptyList(), fixtureTree())

        val sentBody = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(sentBody.contains("t_kitchen"))
        assertTrue(sentBody.contains("home/kitchen-remodel"))
        assertTrue(sentBody.contains("work/mashgin"))
    }

    @Test
    fun `an empty tree sends no tree section at all -- byte-identical to the pre-vn-edu47 prompt`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(messageResponseBody("""{"tags":[]}""")))

        scorer().score("tail", emptyList(), TagTree.EMPTY)

        val sentBody = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue("no tree section expected in the legacy (no-vault) prompt path", !sentBody.contains("Existing tag tree"))
        assertTrue("no tag_id JSON key expected in the legacy prompt", !sentBody.contains("tag_id"))
    }

    @Test
    fun `a tag_id that resolves in the tree carries that id and the node's canonical name, overriding the model's own text`() = runBlocking {
        val body = messageResponseBody("""{"tags":[{"tag":"cabinet stuff","tag_id":"t_kitchen","confidence":0.82,"is_proposal":false}]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = scorer().score("tail", emptyList(), fixtureTree())

        assertEquals(listOf(TagCandidate("kitchen-remodel", 0.82, tagId = "t_kitchen", isProposal = false)), result)
    }

    @Test
    fun `a tag_id that does not resolve in the tree degrades to a plain free-form candidate, not dropped`() = runBlocking {
        val body = messageResponseBody("""{"tags":[{"tag":"some ghost topic","tag_id":"t_does_not_exist","confidence":0.6,"is_proposal":false}]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = scorer().score("tail", emptyList(), fixtureTree())

        assertEquals(listOf(TagCandidate("some ghost topic", 0.6, tagId = null, isProposal = false)), result)
    }

    @Test
    fun `is_proposal true with a null tag_id yields a genuinely new proposal candidate`() = runBlocking {
        val body = messageResponseBody("""{"tags":[{"tag":"gardening","tag_id":null,"confidence":0.58,"is_proposal":true}]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = scorer().score("tail", emptyList(), fixtureTree())

        assertEquals(listOf(TagCandidate("gardening", 0.58, tagId = null, isProposal = true)), result)
    }

    @Test
    fun `a tree-matched candidate and a proposal can both appear in the same response`() = runBlocking {
        val body = messageResponseBody(
            """{"tags":[{"tag":"kitchen-remodel","tag_id":"t_kitchen","confidence":0.9,"is_proposal":false},""" +
                """{"tag":"gardening","tag_id":null,"confidence":0.55,"is_proposal":true}]}""",
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = scorer().score("tail", emptyList(), fixtureTree())

        assertEquals(
            listOf(
                TagCandidate("kitchen-remodel", 0.9, tagId = "t_kitchen", isProposal = false),
                TagCandidate("gardening", 0.55, tagId = null, isProposal = true),
            ),
            result,
        )
    }

    @Test
    fun `more than one proposal in the raw response is gated down to only the highest-confidence one`() = runBlocking {
        val body = messageResponseBody(
            """{"tags":[{"tag":"gardening","tag_id":null,"confidence":0.5,"is_proposal":true},""" +
                """{"tag":"beekeeping","tag_id":null,"confidence":0.7,"is_proposal":true}]}""",
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = scorer().score("tail", emptyList(), fixtureTree())

        assertEquals(listOf(TagCandidate("beekeeping", 0.7, tagId = null, isProposal = true)), result)
    }

    @Test
    fun `even a well-formed is_proposal true is ignored when the tree is empty -- legacy path never marks proposals`() = runBlocking {
        val body = messageResponseBody("""{"tags":[{"tag":"gardening","is_proposal":true,"confidence":0.6}]}""")
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = scorer().score("tail", emptyList(), TagTree.EMPTY)

        assertEquals(listOf(TagCandidate("gardening", 0.6, tagId = null, isProposal = false)), result)
    }
}
