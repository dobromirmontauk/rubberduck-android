package com.montauk.voicecapture.session

/**
 * Truthful placeholder copy for the Canonical/Filed-to tabs (bead vn-edu.57),
 * derived purely from a session's [SessionStatus] -- no I/O, no Compose.
 * Keyless/signed-out never resolves to [SessionStatus.INTEGRATED] (see
 * [com.montauk.voicecapture.vault.VaultSessionReader.refresh]'s keyless-EMPTY
 * contract, threaded through [SessionStatusResolver]), so it falls out of
 * [forStatus] the same way an UPLOADED-but-not-yet-organized session does --
 * there's no separate "signed out" branch, and neither message implies an
 * error for that case.
 */
object DetailTabPlaceholder {
    const val NOT_UPLOADED = "Not uploaded yet"
    const val AWAITING_ORGANIZATION = "Awaiting organization"
    const val LOAD_FAILED = "Couldn't load — check connection"

    /** Null means "status is INTEGRATED -- go fetch the artifact," not "no placeholder applies." */
    fun forStatus(status: SessionStatus): String? = when (status) {
        SessionStatus.LOCAL, SessionStatus.QUEUED -> NOT_UPLOADED
        SessionStatus.UPLOADED -> AWAITING_ORGANIZATION
        SessionStatus.INTEGRATED -> null
    }
}
