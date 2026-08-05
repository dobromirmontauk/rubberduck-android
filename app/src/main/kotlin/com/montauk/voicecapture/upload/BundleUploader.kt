package com.montauk.voicecapture.upload

import java.io.File

/** Why an upload didn't happen or didn't finish. */
sealed class UploadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkUnavailable : UploadError("No network connection")
    class AuthFailed(cause: Throwable? = null) : UploadError("GitHub auth failed", cause)
    class Other(message: String, cause: Throwable? = null) : UploadError(message, cause)
}

/**
 * Ships a finalized session bundle (`audio.ogg` + `meta.json` +
 * `live-transcript.jsonl`) to the git-backed voice-vault.
 *
 * See [GitHubBundleUploader] for the real implementation (Git Data API +
 * Git LFS batch API, pure HTTPS, one commit per session) against the
 * voice-vault repo's ingest contract (`docs/ingest-contract.md` in that repo).
 *
 * [SessionStore] marks a session's upload-state marker file LOCAL ->
 * QUEUED -> UPLOADED around calls to [uploadBundle]; this interface only
 * needs to report success/failure, not manage that state machine itself.
 * [com.montauk.voicecapture.upload.UploadWorker] is what actually drives
 * that state machine from a WorkManager retry queue.
 */
interface BundleUploader {
    /** Uploads the session directory at [sessionDir] (already finalized) to the vault. */
    suspend fun uploadBundle(sessionDir: File): Result<Unit>
}

/** No-op placeholder used whenever no GitHub token is configured (see [BundleUploaderFactory]). */
class NoOpBundleUploader : BundleUploader {
    override suspend fun uploadBundle(sessionDir: File): Result<Unit> =
        Result.failure(UploadError.AuthFailed())
}

/** Builds the right [BundleUploader] for the current configuration. */
object BundleUploaderFactory {
    /** [token] is `BuildConfig.GITHUB_TOKEN`; blank means "not configured". */
    fun create(token: String, owner: String, repo: String): BundleUploader =
        if (token.isBlank()) NoOpBundleUploader() else GitHubBundleUploader(token = token, owner = owner, repo = repo)
}
