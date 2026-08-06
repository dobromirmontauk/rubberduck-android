package com.montauk.voicecapture.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bead vn-edu.52: [credentialDisplayState] is the three-state logic behind
 * Settings' key/token rows -- pure, so this is plain JUnit (no Robolectric).
 * [ApiKeyManagementRowTest] and `SettingsCredentialRowsTest` cover the
 * Compose-level rendering of each state.
 */
class CredentialDisplayStateTest {

    @Test
    fun `runtime value present is CONFIGURED regardless of dev fallback`() {
        assertEquals(CredentialDisplayState.CONFIGURED, credentialDisplayState(hasRuntimeValue = true, hasDevFallback = true))
        assertEquals(CredentialDisplayState.CONFIGURED, credentialDisplayState(hasRuntimeValue = true, hasDevFallback = false))
    }

    @Test
    fun `no runtime value but a dev fallback is DEV_FALLBACK`() {
        assertEquals(CredentialDisplayState.DEV_FALLBACK, credentialDisplayState(hasRuntimeValue = false, hasDevFallback = true))
    }

    @Test
    fun `neither runtime value nor dev fallback is NOT_CONFIGURED`() {
        assertEquals(CredentialDisplayState.NOT_CONFIGURED, credentialDisplayState(hasRuntimeValue = false, hasDevFallback = false))
    }
}
