package com.montauk.voicecapture.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bead vn-edu.48: a runtime-entered key (Settings' Replace flow or the setup
 * wizard's Intelligence step) must always win over the BuildConfig/
 * local.properties dev-build convenience -- these tests pin down that
 * precedence for the Anthropic key exactly the way it already held for the
 * AssemblyAI key, plus signed-out's "everything reads as unconfigured"
 * override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSecretsStoreTest {

    private lateinit var store: AppSecretsStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = AppSecretsStore(context)
    }

    @Test
    fun `runtime Anthropic key wins over the BuildConfig dev fallback`() {
        store.userAnthropicKey = "sk-ant-user-entered"

        assertEquals("sk-ant-user-entered", store.effectiveAnthropicKey("sk-ant-build-config-dev-key"))
    }

    @Test
    fun `blank runtime Anthropic key falls back to BuildConfig`() {
        store.userAnthropicKey = null

        assertEquals("sk-ant-build-config-dev-key", store.effectiveAnthropicKey("sk-ant-build-config-dev-key"))
    }

    @Test
    fun `whitespace-only runtime Anthropic key is treated as unset`() {
        store.userAnthropicKey = "   "

        assertEquals("sk-ant-build-config-dev-key", store.effectiveAnthropicKey("sk-ant-build-config-dev-key"))
    }

    @Test
    fun `signed out forces the effective Anthropic key blank regardless of either source`() {
        store.userAnthropicKey = "sk-ant-user-entered"
        store.isSignedOut = true

        assertEquals("", store.effectiveAnthropicKey("sk-ant-build-config-dev-key"))
    }

    @Test
    fun `runtime Anthropic key persists across a fresh store instance over the same prefs`() {
        store.userAnthropicKey = "sk-ant-persisted"

        val reopened = AppSecretsStore(ApplicationProvider.getApplicationContext())

        assertEquals("sk-ant-persisted", reopened.userAnthropicKey)
    }
}
