package com.montauk.voicecapture.auth

/**
 * Fallback literal from `app/build.gradle.kts`'s `GITHUB_OAUTH_CLIENT_ID`
 * buildConfigField, compiled in whenever `github.oauthClientId` isn't set in
 * `local.properties`. GitHub's device-flow endpoint always rejects it.
 */
const val PLACEHOLDER_GITHUB_OAUTH_CLIENT_ID = "REPLACE_WITH_GITHUB_OAUTH_CLIENT_ID"

/**
 * Pure placeholder check (bead vn-edu.28), kept free of `BuildConfig`/
 * `Application` so it's testable on the JVM. [com.montauk.voicecapture.VoiceCaptureApp.isGithubOAuthConfigured]
 * calls this with `BuildConfig.GITHUB_OAUTH_CLIENT_ID`; [com.montauk.voicecapture.ui.LoginScreen]
 * uses the result to keep "Sign in with GitHub" off a device-flow request
 * that's guaranteed to 404.
 */
fun isGithubOAuthConfigured(clientId: String): Boolean = clientId != PLACEHOLDER_GITHUB_OAUTH_CLIENT_ID
