package com.montauk.voicecapture.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.logging.RubberduckLog
import com.montauk.voicecapture.session.SessionStore
import com.montauk.voicecapture.session.UploadState
import java.util.concurrent.TimeUnit

/**
 * Retry queue for [BundleUploader.uploadBundle]. A session is enqueued once,
 * by name, right after [RecordingService][com.montauk.voicecapture.service.RecordingService]
 * finalizes it; WorkManager owns the network-constraint + exponential-backoff
 * retry loop from there, surviving process death and app restarts.
 *
 * The local session directory is never deleted here regardless of outcome --
 * per [com.montauk.voicecapture.session.SessionStore]'s contract, that's a
 * future retention-policy concern, not this worker's.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            RubberduckLog.i("Upload", "missing_session_id")
            return Result.failure()
        }

        val app = applicationContext as VoiceCaptureApp
        val sessionDir = app.sessionStore.sessionDir(sessionId)
        if (!sessionDir.exists()) {
            RubberduckLog.i("Upload", "session_dir_missing", "sessionId" to sessionId)
            return Result.failure()
        }

        RubberduckLog.i("Upload", "start", "sessionId" to sessionId)
        val uploadResult = app.bundleUploader.uploadBundle(sessionDir)
        return uploadResult.fold(
            onSuccess = {
                app.sessionStore.setUploadState(sessionDir, UploadState.UPLOADED)
                RubberduckLog.i("Upload", "success", "sessionId" to sessionId)
                Result.success()
            },
            onFailure = { e ->
                RubberduckLog.i("Upload", "failed", "sessionId" to sessionId, "reason" to (e.message ?: e::class.simpleName))
                when (e) {
                    // Bad/missing credentials won't fix themselves on retry -- stop burning
                    // the backoff schedule on it. The session stays QUEUED and can be
                    // re-enqueued once configuration is fixed (e.g. next app start).
                    is UploadError.AuthFailed -> Result.failure()
                    else -> Result.retry()
                }
            },
        )
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        private const val WORK_NAME_PREFIX = "upload-session-"

        /**
         * Enqueues (or, if a retry is already pending for this session,
         * leaves alone) a network-constrained, exponential-backoff upload
         * job. Unique by session id so re-finalizing or app-restart recovery
         * never double-enqueues the same session.
         */
        fun enqueue(context: Context, sessionId: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_SESSION_ID to sessionId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$sessionId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Drains the upload backlog (bead vn-edu.29): every session that
         * finalized while signed out landed at [UploadState.LOCAL] instead of
         * [UploadState.QUEUED] (see [com.montauk.voicecapture.service.RecordingService]),
         * since there was no uploader to hand it to. Called once, right after
         * a sign-in actually stores a token -- [SessionStore.localSessionIds]
         * is the pure part of this (session-scan + filter), kept there so
         * it's unit-testable without WorkManager.
         */
        fun enqueueBacklog(context: Context, sessionStore: SessionStore) {
            sessionStore.localSessionIds().forEach { sessionId ->
                sessionStore.setUploadState(sessionStore.sessionDir(sessionId), UploadState.QUEUED)
                enqueue(context, sessionId)
            }
        }
    }
}
