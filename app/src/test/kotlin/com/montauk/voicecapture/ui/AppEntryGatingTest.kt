package com.montauk.voicecapture.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AppEntryGating] is the routing seam wave-4 built for cold-start/post-login
 * decisions. Bead vn-edu.29 removed the login/wizard gate from
 * [AppEntryGating.startDestination] entirely -- sign-in state no longer
 * factors into where the app opens, only an in-progress recording does.
 * [AppEntryGating.postLoginDestination] is unchanged: it still governs where
 * the *connect* flow (now optional, reachable from Settings or the bottom
 * nav) lands after a successful sign-in.
 */
class AppEntryGatingTest {

    @Test
    fun `a fresh signed-out launch with no recording opens on Sessions, not a login gate`() {
        assertEquals(Routes.SESSIONS, AppEntryGating.startDestination(isRecording = false))
    }

    @Test
    fun `an in-progress recording wins over Sessions -- a relaunch never interrupts it`() {
        assertEquals(Routes.RECORDING, AppEntryGating.startDestination(isRecording = true))
    }

    @Test
    fun `postLoginDestination sends a first-time connect to the wizard`() {
        assertEquals(Routes.WIZARD, AppEntryGating.postLoginDestination(setupWizardCompleted = false))
    }

    @Test
    fun `postLoginDestination sends a repeat connect straight to sessions`() {
        assertEquals(Routes.SESSIONS, AppEntryGating.postLoginDestination(setupWizardCompleted = true))
    }
}
