package com.montauk.voicecapture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.audio.AudioEngine
import com.montauk.voicecapture.session.LiveTranscriptLine
import com.montauk.voicecapture.session.LiveTranscriptWriter
import com.montauk.voicecapture.session.SessionHandle
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.stt.StreamingSttClient
import com.montauk.voicecapture.upload.UploadWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import kotlin.concurrent.thread

/**
 * Foreground service that owns the [AudioEngine] for the lifetime of a
 * recording. Started only from a direct user tap (record button) -- Android
 * 14+ forbids starting a microphone foreground service from the background.
 *
 * The persistent notification is the user's only always-visible confirmation
 * that recording is still alive during a 30-60 minute walk/drive, so it shows
 * live elapsed time and a Stop action.
 */
class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.montauk.voicecapture.action.START"
        const val ACTION_STOP = "com.montauk.voicecapture.action.STOP"

        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 1_000L
        // MediaMuxer's native remux path can, on a bad stream, block forever
        // rather than throw (see the watchdog note in endRecording()) --
        // generous enough for a real WAL, short enough that the UI/foreground
        // service never looks stuck to the user.
        private const val FINALIZE_TIMEOUT_MS = 20_000L

        fun startIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }

    private lateinit var audioEngine: AudioEngine
    private var currentSession: SessionHandle? = null
    private var startElapsedRealtimeMs: Long = 0L
    private var sttClient: StreamingSttClient? = null
    private var sttJobs: List<Job> = emptyList()

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioEngine()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> beginRecording()
            ACTION_STOP -> endRecording()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (currentSession != null) return // already recording

        val app = application as VoiceCaptureApp
        val session = app.sessionStore.createSession(Date())
        currentSession = session

        TranscriptStateHolder.reset()
        val stt = app.newSttClient()
        sttClient = stt
        // Tee raw PCM to STT alongside (never instead of) the WAL write below --
        // sendPcm is non-blocking and drops frames under backpressure, so a slow
        // or dead STT connection can never stall or lose audio capture.
        audioEngine.onPcmFrame = { pcm, length -> stt.sendPcm(pcm, 0, length) }

        val walFile = app.sessionStore.walFile(session.dir)
        audioEngine.start(walFile)
        startSttPipeline(stt, session)

        startElapsedRealtimeMs = SystemClock.elapsedRealtime()
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = session.sessionId, elapsedMs = 0L) }

        startForeground(NOTIFICATION_ID, buildNotification(elapsedMs = 0L))
        startTicker()
    }

    /** Connects the STT client and fans its output into the UI state + `live-transcript.jsonl`. */
    private fun startSttPipeline(stt: StreamingSttClient, session: SessionHandle) {
        val app = application as VoiceCaptureApp
        val statusJob = lifecycleScope.launch {
            stt.connectionState().collect { state ->
                TranscriptStateHolder.update { it.copy(connectionState = state) }
            }
        }
        val partialsJob = lifecycleScope.launch {
            stt.partials().collect { partial ->
                TranscriptStateHolder.update { ui ->
                    if (partial.isFinal) {
                        ui.copy(
                            finalLines = ui.finalLines + TranscriptLine(partial.text, partial.startMs, partial.endMs),
                            currentPartial = "",
                        )
                    } else {
                        ui.copy(currentPartial = partial.text)
                    }
                }
                // Ingest contract: live-transcript.jsonl carries only immutable
                // (isFinal) segments -- partials are UI-only, never written to disk.
                if (partial.isFinal && partial.text.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        val line = LiveTranscriptLine(partial.startMs, partial.endMs, partial.text, final = true)
                        app.sessionStore.transcriptFile(session.dir).appendText(LiveTranscriptWriter.encodeLine(line) + "\n")
                    }
                }
            }
        }
        val connectJob = lifecycleScope.launch {
            runCatching { stt.connect(AudioEngine.DEFAULT_SAMPLE_RATE_HZ, channelCount = 1) }
        }
        sttJobs = listOf(statusJob, partialsJob, connectJob)
    }

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive && currentSession != null) {
                val elapsed = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
                RecordingStateHolder.update { it.copy(elapsedMs = elapsed) }
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(elapsed))
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun endRecording() {
        val session = currentSession ?: return
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        currentSession = null

        audioEngine.stop()
        audioEngine.onPcmFrame = null

        val app = application as VoiceCaptureApp
        val stt = sttClient
        sttClient = null
        val jobsToCancel = sttJobs
        sttJobs = emptyList()

        lifecycleScope.launch {
            // Close the STT socket in parallel with the (independent) audio
            // finalize -- neither should wait on the other.
            val sttCloseJob = stt?.let { launch { runCatching { it.close() } } }

            val finalizeSucceeded = runFinalizeWithWatchdog(app, session, elapsedMs)
            if (finalizeSucceeded) {
                UploadWorker.enqueue(applicationContext, session.sessionId)
            }
            // A false/timed-out result deliberately leaves audio.wal in place
            // with no meta.json written -- SessionStore.findUnfinalizedSessions()
            // picks it up as an orphaned recording and VoiceCaptureApp retries
            // the remux on next app launch. The session simply won't appear
            // in the list until then, per docs/audio-wal.md's recovery model.

            sttCloseJob?.join()
            jobsToCancel.forEach { it.cancel() }

            RecordingStateHolder.update { it.copy(isRecording = false, elapsedMs = elapsedMs) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Runs WAL-to-ogg finalization + meta.json write on its own thread and
     * waits on it with a timeout, rather than just dispatching onto
     * [Dispatchers.IO] and hoping for the best. This distinction matters:
     * [AudioEngine.finalizeToOgg] ends in a native `MediaMuxer.stop()` call,
     * and a native call blocked inside a coroutine doesn't respond to
     * coroutine cancellation -- cancellation only takes effect at a
     * suspension point, which a synchronous native call never reaches. Only
     * waiting on a *separate* thread via a genuinely suspending
     * [CompletableDeferred.await] lets [withTimeoutOrNull] actually give up
     * and let the service move on, even if that background thread stays
     * stuck. The leaked thread is an acceptable tradeoff against the UI
     * hanging forever -- see the incident this guards against in the
     * `AudioEngine.probeOpusCodecConfig` fix.
     */
    private suspend fun runFinalizeWithWatchdog(app: VoiceCaptureApp, session: SessionHandle, elapsedMs: Long): Boolean {
        val outcome = CompletableDeferred<Boolean>()
        thread(name = "finalize-${session.sessionId}") {
            val result = runCatching {
                val walFile = app.sessionStore.walFile(session.dir)
                val oggFile = app.sessionStore.oggFile(session.dir)
                audioEngine.finalizeToOgg(walFile, oggFile)
                walFile.delete()
                app.sessionStore.writeMeta(
                    handle = session,
                    durationMs = elapsedMs,
                    deviceModel = Build.MODEL,
                    appVersion = app.appVersionName(),
                )
                app.sessionStore.setUploadState(session.dir, UploadState.QUEUED)
            }
            result.onFailure { e -> Log.e(TAG, "finalize failed for ${session.sessionId}", e) }
            outcome.complete(result.isSuccess)
        }

        val finished = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { outcome.await() }
        if (finished == null) {
            Log.e(
                TAG,
                "finalize timed out after ${FINALIZE_TIMEOUT_MS}ms for ${session.sessionId}; " +
                    "giving up waiting so the UI doesn't hang -- audio.wal is left for recovery on next launch",
            )
        }
        return finished == true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows elapsed time while a voice-capture session is recording"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val elapsedText = formatElapsed(elapsedMs)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Recording")
            .setContentText(elapsedText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
