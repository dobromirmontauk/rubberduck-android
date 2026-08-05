package com.montauk.voicecapture.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isGithubOAuthConfigured] gates whether the login screen's "Sign in with
 * GitHub" button drives a device-flow request that's guaranteed to succeed
 * or one that's guaranteed to 404 (bead vn-edu.28).
 */
class GitHubOAuthConfigTest {

    @Test
    fun `placeholder client id is not configured`() {
        assertFalse(isGithubOAuthConfigured(PLACEHOLDER_GITHUB_OAUTH_CLIENT_ID))
    }

    @Test
    fun `a real client id is configured`() {
        assertTrue(isGithubOAuthConfigured("Iv1.abcdef1234567890"))
    }
}
