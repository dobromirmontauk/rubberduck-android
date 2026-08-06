package com.montauk.voicecapture.service

import android.util.Log
import java.io.File

/**
 * Bead vn-edu.56: deletes a whole session directory -- audio.wal (or
 * whatever partial bundle exists at Stop-time; a discarded session never
 * gets far enough to have audio.ogg/meta.json) -- when [TooShortDecision]
 * resolves to "discard". A plain [File] operation, split out from
 * [RecordingService] so it's unit-testable against a real temp directory
 * without any Android/Robolectric dependency.
 *
 * Deleting the whole directory (not just audio.wal) also matters for
 * [com.montauk.voicecapture.session.SessionStore.findUnfinalizedSessions]:
 * leaving audio.wal behind with the directory otherwise intact would make
 * app-launch recovery mistake a deliberately-discarded session for a
 * crashed one and try to finalize it anyway.
 */
object TooShortSessionDiscarder {
    private const val TAG = "TooShortSessionDiscarder"

    /** Best-effort recursive delete of [sessionDir]. Returns true if [sessionDir] no longer exists afterward. */
    fun discard(sessionDir: File): Boolean {
        val deleted = runCatching { sessionDir.deleteRecursively() }
            .onFailure { e -> Log.e(TAG, "failed to delete discarded session dir $sessionDir", e) }
            .getOrDefault(false)
        if (!deleted && sessionDir.exists()) {
            Log.e(TAG, "discarded session dir $sessionDir still exists after deleteRecursively()")
        }
        return !sessionDir.exists()
    }
}
