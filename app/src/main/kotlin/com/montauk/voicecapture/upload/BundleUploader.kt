package com.montauk.voicecapture.upload

import java.io.File

/** Why an upload didn't happen or didn't finish. */
sealed class UploadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkUnavailable : UploadError("No network connection")
    class AuthFailed(cause: Throwable? = null) : UploadError("GitHub auth failed", cause)
    class Other(message: String, cause: Throwable? = null) : UploadError(message, cause)
}

/**
 * Stub for shipping a finalized session bundle (`audio.ogg` + `meta.json` +
 * `live-transcript.jsonl`) to the git-backed voice-vault.
 *
 * Not implemented yet. The planned approach: commit the bundle via the
 * GitHub REST contents API for the small text files (meta.json,
 * live-transcript.jsonl) and the Git LFS batch API for `audio.ogg` (session
 * audio routinely runs tens of MB, well past what the contents API's base64
 * inline-blob path handles comfortably). See voice-vault repo docs for the
 * ingest contract this uploads against.
 *
 * [SessionStore] marks a session's upload-state marker file LOCAL ->
 * QUEUED -> UPLOADED around calls to [uploadBundle]; this interface only
 * needs to report success/failure, not manage that state machine itself.
 */
interface BundleUploader {
    /** Uploads the session directory at [sessionDir] (already finalized) to the vault. */
    suspend fun uploadBundle(sessionDir: File): Result<Unit>
}

/** No-op placeholder so the UI's upload-state chip has something to call before the real uploader exists. */
class NoOpBundleUploader : BundleUploader {
    override suspend fun uploadBundle(sessionDir: File): Result<Unit> =
        Result.failure(UploadError.Other("BundleUploader not implemented yet"))
}
