package com.montauk.voicecapture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bead vn-edu.53: release builds must never embed real secrets, regardless
 * of what's in local.properties or the environment on the machine that ran
 * `./gradlew assembleRelease` -- users configure keys in-app instead
 * (AppSecretsStore, vn-edu.48). This lives under src/testRelease so it only
 * runs as part of testReleaseUnitTest and compiles against the *release*
 * variant's BuildConfig (see app/build.gradle.kts sourceSets) -- the same
 * assertion against the debug variant would legitimately fail, since debug
 * intentionally bakes in local.properties/env secrets for dev convenience.
 */
class ReleaseSecretsBlankTest {

    @Test
    fun `assemblyai api key is blank in release`() {
        assertEquals("", BuildConfig.ASSEMBLYAI_API_KEY)
    }

    @Test
    fun `anthropic api key is blank in release`() {
        assertEquals("", BuildConfig.ANTHROPIC_API_KEY)
    }

    @Test
    fun `github token is blank in release`() {
        assertEquals("", BuildConfig.GITHUB_TOKEN)
    }
}
