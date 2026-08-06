package com.montauk.voicecapture.vault

import java.util.Base64
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
 * [GitHubVaultSessionSource] against a mock HTTP server standing in for
 * GitHub's Contents API (bead vn-edu.54), mirroring
 * [com.montauk.voicecapture.upload.GitHubBundleUploaderIdempotencyTest]'s
 * approach: the directory-listing form of the same endpoint
 * [com.montauk.voicecapture.tags.GitHubTagTreeSource] already uses for a
 * single file's contents.
 */
class GitHubVaultSessionSourceTest {

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

    private fun source(): GitHubVaultSessionSource = GitHubVaultSessionSource(
        apiBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `listSessionIds parses only dir entries out of the sessions listing`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {"name": "2026-08-01_0900_ab12", "type": "dir"},
                  {"name": "2026-08-02_1000_cd34", "type": "dir"},
                  {"name": ".gitkeep", "type": "file"}
                ]
                """.trimIndent(),
            ),
        )

        val ids = source().listSessionIds(token = "tok", owner = "o", repo = "r")

        assertEquals(setOf("2026-08-01_0900_ab12", "2026-08-02_1000_cd34"), ids)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/repos/o/r/contents/sessions"))
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
    }

    @Test
    fun `a 404 on the sessions listing -- nothing ever organized yet -- is an empty set, not a failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message": "Not Found"}"""))

        val ids = source().listSessionIds(token = "tok", owner = "o", repo = "r")

        assertEquals(emptySet<String>(), ids)
    }

    @Test
    fun `an unexpected status code returns null, not a false empty`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val ids = source().listSessionIds(token = "tok", owner = "o", repo = "r")

        assertNull(ids)
    }

    @Test
    fun `a blank token never hits the network`() = runBlocking {
        val ids = source().listSessionIds(token = "", owner = "o", repo = "r")

        assertNull(ids)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fetchArtifact decodes the base64 contents body`() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString("# Organized\n\nSome notes.".toByteArray())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content": "$encoded", "encoding": "base64"}"""))

        val bytes = source().fetchArtifact(token = "tok", owner = "o", repo = "r", sessionId = "2026-08-01_0900_ab12", filename = "organization.md")

        assertEquals("# Organized\n\nSome notes.", bytes?.toString(Charsets.UTF_8))
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/repos/o/r/contents/sessions/2026-08-01_0900_ab12/organization.md"))
    }

    @Test
    fun `fetchArtifact returns null on a 404 -- artifact not present for this session`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val bytes = source().fetchArtifact(token = "tok", owner = "o", repo = "r", sessionId = "missing", filename = "organization.md")

        assertNull(bytes)
    }
}
