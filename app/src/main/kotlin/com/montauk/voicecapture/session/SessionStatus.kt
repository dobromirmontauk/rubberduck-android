package com.montauk.voicecapture.session

/**
 * Display status for a session row (bead vn-edu.54): LOCAL/QUEUED/UPLOADED
 * mirror [UploadState] one-for-one; INTEGRATED is a fourth, vault-derived
 * status layered on top of it. Kept as a separate type rather than adding a
 * fourth [UploadState] value because upload state is bookkeeping persisted to
 * the `.upload-state` marker file by the uploader/re-upload action, while
 * INTEGRATED is derived fresh from the vault's `sessions/` listing on every
 * screen visit (see [com.montauk.voicecapture.vault.VaultSessionReader]) and
 * never written to disk itself.
 */
enum class SessionStatus { LOCAL, QUEUED, UPLOADED, INTEGRATED }

object SessionStatusResolver {
    /**
     * [integratedSessionIds] is the vault's `sessions/` directory listing --
     * only an UPLOADED session can graduate to INTEGRATED (the vault's
     * organize skill only ever processes what's already landed in its
     * append-only `inbox/`); a session the vault doesn't know about, or one
     * still LOCAL/QUEUED, resolves one-to-one with its [UploadState].
     */
    fun resolve(uploadState: UploadState, sessionId: String, integratedSessionIds: Set<String>): SessionStatus =
        if (uploadState == UploadState.UPLOADED && sessionId in integratedSessionIds) {
            SessionStatus.INTEGRATED
        } else {
            when (uploadState) {
                UploadState.LOCAL -> SessionStatus.LOCAL
                UploadState.QUEUED -> SessionStatus.QUEUED
                UploadState.UPLOADED -> SessionStatus.UPLOADED
            }
        }
}
