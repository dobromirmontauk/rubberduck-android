package com.montauk.voicecapture.session

/**
 * Bead vn-edu.55: pure decision logic behind "delete from phone" (per-session)
 * and "archive all integrated sessions" (bulk) -- kept independent of
 * Compose/Android so both are covered by ordinary JVM unit tests with no
 * Robolectric needed. The on-disk removal itself is [SessionStore.deleteSession];
 * neither function here takes (or could reach) a
 * [com.montauk.voicecapture.upload.BundleUploader] or any vault/GitHub
 * client -- these actions are LOCAL-ONLY by construction, not by convention.
 */
object DeleteConfirmPolicy {
    /**
     * True when deleting a session in [uploadState] right now would lose
     * audio that never left the device -- LOCAL (never queued for upload) or
     * QUEUED (queued but not yet confirmed landed). Only UPLOADED is safe
     * enough for a plain confirm; [SessionStatus.INTEGRATED] per
     * [SessionStatusResolver] only ever layers on top of an already-UPLOADED
     * session, so callers gate on [UploadState] here, not the derived
     * [SessionStatus].
     */
    fun requiresHardWarning(uploadState: UploadState): Boolean = uploadState != UploadState.UPLOADED
}

object BulkArchiveEligibility {
    /**
     * Ids of every session in [sessions] whose derived status (via
     * [SessionStatusResolver], fed [integratedSessionIds] the same way the
     * Sessions screen's per-row chip is) is exactly [SessionStatus.INTEGRATED].
     * A session that's merely UPLOADED -- landed in the vault but not yet
     * organized -- is deliberately excluded: the vault holding a copy isn't
     * the same guarantee as the vault having processed it.
     */
    fun eligibleSessionIds(sessions: List<SessionSummary>, integratedSessionIds: Set<String>): List<String> =
        sessions
            .filter { SessionStatusResolver.resolve(it.uploadState, it.sessionId, integratedSessionIds) == SessionStatus.INTEGRATED }
            .map { it.sessionId }
}
