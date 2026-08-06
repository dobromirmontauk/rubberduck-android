package com.montauk.voicecapture.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [GitHubAccountClient] backs the login screen's access-token validation
 * ("Use an access token" -> `GET /user`) and the setup wizard's vault picker
 * (`GET /user/repos`, then a per-repo `docs/ingest-contract.md` check).
 * Exercised against a mock HTTP server standing in for `api.github.com`.
 */
class GitHubAccountClientTest {

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

    private fun client(): GitHubAccountClient =
        GitHubAccountClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `validateToken returns the identity on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"login":"dobromirmontauk","avatar_url":"https://example.com/a.png"}"""))

        val result = client().validateToken("fake-token")

        assertTrue(result.isSuccess)
        assertEquals("dobromirmontauk", result.getOrThrow().login)
    }

    @Test
    fun `validateToken surfaces 401 as InvalidToken, not a generic failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client().validateToken("bad-token")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitHubAccountError.InvalidToken)
    }

    @Test
    fun `listRepos parses full_name and owner, sorted order preserved from the server`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"name":"voice-vault","full_name":"dobromirmontauk/voice-vault","owner":{"login":"dobromirmontauk"},"updated_at":"2026-08-04T00:00:00Z"}]""",
            ),
        )

        val result = client().listRepos("fake-token")

        assertTrue(result.isSuccess)
        val repos = result.getOrThrow()
        assertEquals(1, repos.size)
        assertEquals("dobromirmontauk/voice-vault", repos[0].fullName)
        assertEquals("voice-vault", repos[0].name)
    }

    @Test
    fun `hasIngestContract is true on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"ingest-contract.md","type":"file"}"""))

        val result = client().hasIngestContract("fake-token", "dobromirmontauk", "voice-vault")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `hasIngestContract is false on 404, not a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client().hasIngestContract("fake-token", "dobromirmontauk", "some-other-repo")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    // Bead vn-edu.30 -- over-scope detection: GitHub sends X-OAuth-Scopes on
    // classic PAT / OAuth App responses and omits it entirely for
    // fine-grained PATs, so its presence is the only signal available here.

    @Test
    fun `validateToken flags isOverScoped when the response carries X-OAuth-Scopes (classic token)`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("X-OAuth-Scopes", "repo")
                .setBody("""{"login":"dobromirmontauk","avatar_url":null}"""),
        )

        val result = client().validateToken("classic-token")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isOverScoped)
    }

    @Test
    fun `validateToken leaves isOverScoped false when no X-OAuth-Scopes header is present (fine-grained PAT)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"login":"dobromirmontauk","avatar_url":null}"""))

        val result = client().validateToken("fine-grained-token")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().isOverScoped)
    }

    @Test
    fun `hasRepoAccess is true on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"voice-vault"}"""))

        val result = client().hasRepoAccess("fake-token", "dobromirmontauk", "voice-vault")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `hasRepoAccess is false on 404 -- a scoped-elsewhere token can't see this repo`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client().hasRepoAccess("fake-token", "dobromirmontauk", "voice-vault")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `hasRepoAccess surfaces 401 as InvalidToken`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client().hasRepoAccess("bad-token", "dobromirmontauk", "voice-vault")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitHubAccountError.InvalidToken)
    }

    // Bead vn-edu.30 -- validateForVault: the combined gate the login screen's
    // PAT entry runs before ever persisting a token.

    @Test
    fun `validateForVault succeeds when the token is valid and can reach the vault repo`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"login":"dobromirmontauk","avatar_url":null}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"voice-vault"}"""))

        val result = client().validateForVault("fake-token", "dobromirmontauk", "voice-vault")

        assertTrue(result.isSuccess)
        assertEquals("dobromirmontauk", result.getOrThrow().login)
    }

    @Test
    fun `validateForVault fails with RepoNotAccessible when the token is valid but can't reach the vault repo`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"login":"dobromirmontauk","avatar_url":null}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client().validateForVault("fake-token", "dobromirmontauk", "voice-vault")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is GitHubAccountError.RepoNotAccessible)
        assertEquals("dobromirmontauk", (error as GitHubAccountError.RepoNotAccessible).owner)
        assertEquals("voice-vault", error.repo)
    }

    @Test
    fun `validateForVault fails with InvalidToken and never checks repo access when the token itself is bad`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client().validateForVault("bad-token", "dobromirmontauk", "voice-vault")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitHubAccountError.InvalidToken)
        assertEquals(1, server.requestCount)
    }
}
