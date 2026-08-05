package com.montauk.voicecapture.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wizard step gating + the first-run flag (vn-edu.15's acceptance criteria:
 * "new user lands in wizard after login", "wizard never blocks recording")
 * and the dev-flow-unbroken guarantee from vn-edu.14. No Android Context
 * needed -- [AppEntryGating] is pure.
 */
class AppEntryGatingTest {

    @Test
    fun `signed out always goes to login, regardless of other state`() {
        val destination = AppEntryGating.startDestination(
            isSignedOut = true,
            isRecording = true,
            setupWizardCompleted = false,
            hasSignedInGithub = true,
        )

        assertEquals(Routes.LOGIN, destination)
    }

    @Test
    fun `an in-progress recording wins over an unfinished wizard -- the wizard never blocks recording`() {
        val destination = AppEntryGating.startDestination(
            isSignedOut = false,
            isRecording = true,
            setupWizardCompleted = false,
            hasSignedInGithub = true,
        )

        assertEquals(Routes.RECORDING, destination)
    }

    @Test
    fun `a fresh github sign-in with the wizard not yet run lands in the wizard`() {
        val destination = AppEntryGating.startDestination(
            isSignedOut = false,
            isRecording = false,
            setupWizardCompleted = false,
            hasSignedInGithub = true,
        )

        assertEquals(Routes.WIZARD, destination)
    }

    @Test
    fun `a completed wizard goes straight to sessions`() {
        val destination = AppEntryGating.startDestination(
            isSignedOut = false,
            isRecording = false,
            setupWizardCompleted = true,
            hasSignedInGithub = true,
        )

        assertEquals(Routes.SESSIONS, destination)
    }

    @Test
    fun `a BuildConfig-only dev build never signed in through the login screen never sees the wizard`() {
        val destination = AppEntryGating.startDestination(
            isSignedOut = false,
            isRecording = false,
            setupWizardCompleted = false,
            hasSignedInGithub = false,
        )

        assertEquals(Routes.SESSIONS, destination)
    }

    @Test
    fun `postLoginDestination sends a first-time sign-in to the wizard`() {
        assertEquals(Routes.WIZARD, AppEntryGating.postLoginDestination(setupWizardCompleted = false))
    }

    @Test
    fun `postLoginDestination sends a returning signed-in user straight to sessions`() {
        assertEquals(Routes.SESSIONS, AppEntryGating.postLoginDestination(setupWizardCompleted = true))
    }
}
