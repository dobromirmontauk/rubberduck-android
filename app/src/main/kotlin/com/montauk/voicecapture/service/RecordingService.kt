package com.montauk.voicecapture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.audio.AudioEngine
import com.montauk.voicecapture.session.SessionHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

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

        private const val NOTIFICATION_CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 1_000L

        fun startIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }

    private lateinit var audioEngine: AudioEngine
    private var currentSession: SessionHandle? = null
    private var startElapsedRealtimeMs: Long = 0L

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

        val walFile = app.sessionStore.walFile(session.dir)
        audioEngine.start(walFile)

        startElapsedRealtimeMs = SystemClock.elapsedRealtime()
        RecordingStateHolder.update { it.copy(isRecording = true, sessionId = session.sessionId, elapsedMs = 0L) }

        startForeground(NOTIFICATION_ID, buildNotification(elapsedMs = 0L))
        startTicker()
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

        val app = application as VoiceCaptureApp
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
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
            }
            RecordingStateHolder.update { it.copy(isRecording = false, elapsedMs = elapsedMs) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
