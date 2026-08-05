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

    fun effectiveGithubToken(buildConfigToken: String): String {
        if (isSignedOut) return ""
        return userGithubToken?.takeIf { it.isNotBlank() } ?: buildConfigToken
    }

    fun effectiveAssemblyKey(buildConfigKey: String): String {
        if (isSignedOut) return ""
        return buildConfigKey
    }

    /** True once the login screen has stored a real, user-entered token (device flow or PAT). */
    fun hasSignedInWithGithub(): Boolean = !userGithubToken.isNullOrBlank()

    companion object {
        private const val PREFS_NAME = "voice_capture_secrets"
        private const val KEY_SIGNED_OUT = "signed_out"
        private const val KEY_USER_GITHUB_TOKEN = "user_github_token"
        private const val KEY_USER_GITHUB_LOGIN = "user_github_login"
    }
}
