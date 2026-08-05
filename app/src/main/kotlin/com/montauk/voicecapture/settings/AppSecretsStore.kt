package com.montauk.voicecapture.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal, wave-3 stand-in for the real credential store that lands with the
 * login screen + setup wizard (a later wave). There is no runtime-entered
 * key yet -- both secrets are still compiled in from
 * `local.properties`/`BuildConfig` -- so "logging out" doesn't erase
 * anything on disk; it flips a persisted flag that makes the app *behave*
 * as if no keys are configured (STT off, upload disabled) until "restore
 * from build config" flips it back. See [com.montauk.voicecapture.VoiceCaptureApp].
 */
class AppSecretsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isSignedOut: Boolean
        get() = prefs.getBoolean(KEY_SIGNED_OUT, false)
        set(value) = prefs.edit().putBoolean(KEY_SIGNED_OUT, value).apply()

    fun effectiveAssemblyKey(buildConfigKey: String): String = if (isSignedOut) "" else buildConfigKey
    fun effectiveGithubToken(buildConfigToken: String): String = if (isSignedOut) "" else buildConfigToken

    companion object {
        private const val PREFS_NAME = "voice_capture_secrets"
        private const val KEY_SIGNED_OUT = "signed_out"
    }
}
