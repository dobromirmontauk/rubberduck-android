package com.montauk.voicecapture.upload

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [GitHubBundleUploader.checkAlreadyUploaded] is the idempotency guard that
 * lets a crashed-mid-upload retry safely skip straight to "done" instead of
 * re-landing a duplicate commit. Exercised here against a mock HTTP server
 * standing in for the GitHub contents API, per the ingest contract: a 200 on
 * `inbox/<session-id>` means the bundle already landed at HEAD.
 */
class GitHubBundleUploaderIdempotencyTest {

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

    private fun uploader(): GitHubBundleUploader = GitHubBundleUploader(
        token = "fake-token",
        owner = "dobromirmontauk",
        repo = "voice-vault",
        apiBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `200 on inbox contents means already uploaded`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name": "2026-08-04_1430_q7x2", "type": "dir"}"""))

        val result = uploader().checkAlreadyUploaded("2026-08-04_1430_q7x2")

        assertTrue(result)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/repos/dobromirmontauk/voice-vault/contents/inbox/2026-08-04_1430_q7x2"))
        assertEquals("Bearer fake-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `404 on inbox contents means not yet uploaded`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message": "Not Found"}"""))

        val result = uploader().checkAlreadyUploaded("2026-08-04_1430_q7x2")

        assertFalse(result)
    }

    @Test(expected = UploadError.AuthFailed::class)
    fun `401 on inbox contents surfaces as an auth failure, not a false negative`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message": "Bad credentials"}"""))

        uploader().checkAlreadyUploaded("2026-08-04_1430_q7x2")
    }

    @Test(expected = UploadError.Other::class)
    fun `unexpected status codes are not silently treated as not-uploaded`() {
        server.enqueue(MockResponse().setResponseCode(500))

        uploader().checkAlreadyUploaded("2026-08-04_1430_q7x2")
    }
}
