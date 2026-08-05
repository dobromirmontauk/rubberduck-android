package com.montauk.voicecapture.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Credential + setup state store, backing the login screen and setup
 * wizard. A user-entered GitHub token (device flow or pasted access token)
 * always wins over the `local.properties`/`BuildConfig` dev fallback; with
 * neither, the app runs local-only (STT off, upload disabled). Logging out
 * doesn't erase [userGithubToken] -- it flips [isSignedOut], which makes
 * every `effective*` getter behave as if nothing is configured until the
 * user signs back in. See [com.montauk.voicecapture.VoiceCaptureApp].
 */
class AppSecretsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isSignedOut: Boolean
        get() = prefs.getBoolean(KEY_SIGNED_OUT, false)
        set(value) = prefs.edit().putBoolean(KEY_SIGNED_OUT, value).apply()

    /** Set by the login screen on a successful device-flow or access-token sign-in. */
    var userGithubToken: String?
        get() = prefs.getString(KEY_USER_GITHUB_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_USER_GITHUB_TOKEN, value).apply()

    /** Cached identity for display (settings, wizard step 1) -- never re-derive from the token on every read. */
    var userGithubLogin: String?
        get() = prefs.getString(KEY_USER_GITHUB_LOGIN, null)
        set(value) = prefs.edit().putString(KEY_USER_GITHUB_LOGIN, value).apply()

    /** Set by the setup wizard's transcription step; skippable, so this is commonly null. */
    var userAssemblyAiKey: String?
        get() = prefs.getString(KEY_USER_ASSEMBLYAI_KEY, null)
        set(value) = prefs.edit().putString(KEY_USER_ASSEMBLYAI_KEY, value).apply()

    /**
     * Set by the setup wizard's Intelligence step (word cloud + titles) or
     * Settings' Replace flow (bead vn-edu.48); skippable, so this is commonly
     * null. Powers [com.montauk.voicecapture.tags.AnthropicTagScorer] and
     * [com.montauk.voicecapture.session.AnthropicTitleGenerator] -- see
     * [effectiveAnthropicKey].
     */
    var userAnthropicKey: String?
        get() = prefs.getString(KEY_USER_ANTHROPIC_KEY, null)
        set(value) = prefs.edit().putString(KEY_USER_ANTHROPIC_KEY, value).apply()

    /** The vault repo picked in the wizard's step 2; null until a first sign-in has run it. */
    var selectedVaultOwner: String?
        get() = prefs.getString(KEY_SELECTED_VAULT_OWNER, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_VAULT_OWNER, value).apply()

    var selectedVaultRepo: String?
        get() = prefs.getString(KEY_SELECTED_VAULT_REPO, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_VAULT_REPO, value).apply()

    /**
     * First-run flag: false until the wizard has been finished (or
     * dismissed) once. Gates whether [Routes.WIZARD][com.montauk.voicecapture.ui.Routes]
     * is the post-sign-in destination; re-running it from Settings doesn't
     * touch this -- it's "has run once ever", not "is currently valid".
     */
    var setupWizardCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_WIZARD_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_WIZARD_COMPLETED, value).apply()

    fun effectiveGithubToken(buildConfigToken: String): String {
        if (isSignedOut) return ""
        return userGithubToken?.takeIf { it.isNotBlank() } ?: buildConfigToken
    }

    fun effectiveAssemblyKey(buildConfigKey: String): String {
        if (isSignedOut) return ""
        return userAssemblyAiKey?.takeIf { it.isNotBlank() } ?: buildConfigKey
    }

    /**
     * Same precedence rule as [effectiveAssemblyKey]: a runtime-entered
     * Anthropic key always wins over the `local.properties`/`BuildConfig`
     * dev fallback (bead vn-edu.48) -- the latter remains a dev-build
     * convenience only, never surfaced to a real user as "configured."
     */
    fun effectiveAnthropicKey(buildConfigKey: String): String {
        if (isSignedOut) return ""
        return userAnthropicKey?.takeIf { it.isNotBlank() } ?: buildConfigKey
    }

    /** True once the login screen has stored a real, user-entered token (device flow or PAT). */
    fun hasSignedInWithGithub(): Boolean = !userGithubToken.isNullOrBlank()

    /**
     * True when GitHub is actually connected right now: a real sign-in has
     * happened AND the user hasn't since logged out. Bead vn-edu.29 -- login
     * is no longer a gate, so "connected" (not "signed in") is what the
     * bottom nav's 4th tab and Settings' "Connect GitHub" affordance key off
     * of. Deliberately excludes the BuildConfig/local.properties dev-token
     * fallback: that's a build-time convenience for running against a real
     * vault without going through sign-in, not a user connecting an account.
     */
    fun isConnectedToGithub(): Boolean = hasSignedInWithGithub() && !isSignedOut

    companion object {
        private const val PREFS_NAME = "voice_capture_secrets"
        private const val KEY_SIGNED_OUT = "signed_out"
        private const val KEY_USER_GITHUB_TOKEN = "user_github_token"
        private const val KEY_USER_GITHUB_LOGIN = "user_github_login"
        private const val KEY_USER_ASSEMBLYAI_KEY = "user_assemblyai_key"
        private const val KEY_USER_ANTHROPIC_KEY = "user_anthropic_key"
        private const val KEY_SELECTED_VAULT_OWNER = "selected_vault_owner"
        private const val KEY_SELECTED_VAULT_REPO = "selected_vault_repo"
        private const val KEY_SETUP_WIZARD_COMPLETED = "setup_wizard_completed"
    }
}
