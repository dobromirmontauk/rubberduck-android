package com.montauk.voicecapture

import android.app.Application
import android.os.Build
import android.util.Log
import com.montauk.voicecapture.audio.AudioEngine
import com.montauk.voicecapture.auth.isGithubOAuthConfigured
import com.montauk.voicecapture.session.SessionStore
import com.montauk.voicecapture.settings.AppSecretsStore
import com.montauk.voicecapture.stt.SttClientFactory
import com.montauk.voicecapture.stt.StreamingSttClient
import com.montauk.voicecapture.upload.BundleUploader
import com.montauk.voicecapture.upload.BundleUploaderFactory
import java.io.File
import kotlin.concurrent.thread

class VoiceCaptureApp : Application() {

    lateinit var sessionStore: SessionStore
        private set

    lateinit var secretsStore: AppSecretsStore
        private set

    /**
     * Shared across sessions; safe to reuse since it's stateless beyond its
     * OkHttp connection pool. [refreshBundleUploader] rebuilds it whenever
     * [secretsStore]'s sign-in state changes (log out / restore from build
     * config) so an in-flight [com.montauk.voicecapture.upload.UploadWorker]
     * run picks up the new effective token on its next enqueue.
     */
    var bundleUploader: BundleUploader = BundleUploaderFactory.create(token = "", owner = "", repo = "")
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(File(filesDir, "sessions"))
        secretsStore = AppSecretsStore(this)
        refreshBundleUploader()
        recoverUnfinalizedSessions()
    }

    /** A fresh [StreamingSttClient] per recording session -- it owns one WebSocket connection's lifecycle. */
    fun newSttClient(): StreamingSttClient =
        SttClientFactory.create(secretsStore.effectiveAssemblyKey(BuildConfig.ASSEMBLYAI_API_KEY))

    fun refreshBundleUploader() {
        bundleUploader = BundleUploaderFactory.create(
            token = secretsStore.effectiveGithubToken(BuildConfig.GITHUB_TOKEN),
            // The wizard's vault-picker step (vn-edu.15) can point this at a
            // different repo than the dev-build default; falls back to the
            // BuildConfig default until that step has run.
            owner = secretsStore.selectedVaultOwner ?: BuildConfig.VAULT_OWNER,
            repo = secretsStore.selectedVaultRepo ?: BuildConfig.VAULT_REPO,
        )
    }

    /** Booleans only for the settings screen -- never surface the actual key/token values. */
    fun isAssemblyKeyConfigured(): Boolean = secretsStore.effectiveAssemblyKey(BuildConfig.ASSEMBLYAI_API_KEY).isNotBlank()
    fun isGithubTokenConfigured(): Boolean = secretsStore.effectiveGithubToken(BuildConfig.GITHUB_TOKEN).isNotBlank()

    /**
     * False whenever no real GitHub OAuth App has been registered (bead
     * vn-edu.28): [BuildConfig.GITHUB_OAUTH_CLIENT_ID] is still the
     * placeholder from `app/build.gradle.kts`, which GitHub's device-flow
     * endpoint always 404s on. [ui.LoginScreen][com.montauk.voicecapture.ui.LoginScreen]
     * uses this to keep the primary sign-in button off of a path that can
     * never succeed, rather than let the user hit that 404. See
     * [com.montauk.voicecapture.auth.isGithubOAuthConfigured] for the pure
     * (JVM-testable) check this delegates to.
     */
    fun isGithubOAuthConfigured(): Boolean = isGithubOAuthConfigured(BuildConfig.GITHUB_OAUTH_CLIENT_ID)

    fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown"

    /**
     * Crash-recovery path from docs/audio-wal.md: a session whose process
     * died mid-recording leaves audio.wal but no audio.ogg/meta.json behind.
     * Finish finalizing it now so it shows up in the session list instead of
     * silently vanishing.
     */
    private fun recoverUnfinalizedSessions() {
        val unfinalized = sessionStore.findUnfinalizedSessions()
        if (unfinalized.isEmpty()) return

        thread(name = "session-recovery") {
            val engine = AudioEngine()
            for (handle in unfinalized) {
                runCatching {
                    val walFile = sessionStore.walFile(handle.dir)
                    val oggFile = sessionStore.oggFile(handle.dir)
                    engine.finalizeToOgg(walFile, oggFile)
                    val durationMs = oggFile.let { estimateDurationFromWalMtime(handle) }
                    sessionStore.writeMeta(
                        handle = handle,
                        durationMs = durationMs,
                        deviceModel = Build.MODEL,
                        appVersion = appVersionName(),
                    )
                    walFile.delete()
                }.onFailure { e ->
                    Log.e("VoiceCaptureApp", "Failed to recover session ${handle.sessionId}", e)
                }
            }
        }
    }

    private fun estimateDurationFromWalMtime(handle: com.montauk.voicecapture.session.SessionHandle): Long {
        val walFile = sessionStore.walFile(handle.dir)
        return (walFile.lastModified() - handle.startedAt.time).coerceAtLeast(0L)
    }
}
