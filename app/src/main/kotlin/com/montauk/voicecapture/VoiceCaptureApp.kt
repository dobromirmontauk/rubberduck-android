package com.montauk.voicecapture

import android.app.Application
import android.os.Build
import android.util.Log
import com.montauk.voicecapture.audio.AudioEngine
import com.montauk.voicecapture.session.SessionStore
import com.montauk.voicecapture.stt.SttClientFactory
import com.montauk.voicecapture.stt.StreamingSttClient
import com.montauk.voicecapture.upload.BundleUploader
import com.montauk.voicecapture.upload.BundleUploaderFactory
import java.io.File
import kotlin.concurrent.thread

class VoiceCaptureApp : Application() {

    lateinit var sessionStore: SessionStore
        private set

    /** Shared across sessions; safe to reuse since it's stateless beyond its OkHttp connection pool. */
    lateinit var bundleUploader: BundleUploader
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(File(filesDir, "sessions"))
        bundleUploader = BundleUploaderFactory.create(
            token = BuildConfig.GITHUB_TOKEN,
            owner = BuildConfig.VAULT_OWNER,
            repo = BuildConfig.VAULT_REPO,
        )
        recoverUnfinalizedSessions()
    }

    /** A fresh [StreamingSttClient] per recording session -- it owns one WebSocket connection's lifecycle. */
    fun newSttClient(): StreamingSttClient = SttClientFactory.create(BuildConfig.ASSEMBLYAI_API_KEY)

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
