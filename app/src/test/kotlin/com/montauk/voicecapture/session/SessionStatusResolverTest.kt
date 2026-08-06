package com.montauk.voicecapture.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure status-derivation logic for the Sessions screen (bead vn-edu.54): no
 * disk, no network, no Compose -- just [UploadState] + a vault listing in,
 * [SessionStatus] out.
 */
class SessionStatusResolverTest {

    @Test
    fun `LOCAL stays LOCAL regardless of the vault listing`() {
        val status = SessionStatusResolver.resolve(UploadState.LOCAL, "s1", integratedSessionIds = setOf("s1"))
        assertEquals(SessionStatus.LOCAL, status)
    }

    @Test
    fun `QUEUED stays QUEUED regardless of the vault listing`() {
        val status = SessionStatusResolver.resolve(UploadState.QUEUED, "s1", integratedSessionIds = setOf("s1"))
        assertEquals(SessionStatus.QUEUED, status)
    }

    @Test
    fun `UPLOADED with no vault hit for this session id stays UPLOADED`() {
        val status = SessionStatusResolver.resolve(UploadState.UPLOADED, "s1", integratedSessionIds = setOf("some-other-session"))
        assertEquals(SessionStatus.UPLOADED, status)
    }

    @Test
    fun `UPLOADED with a vault hit for this exact session id graduates to INTEGRATED`() {
        val status = SessionStatusResolver.resolve(UploadState.UPLOADED, "s1", integratedSessionIds = setOf("s1"))
        assertEquals(SessionStatus.INTEGRATED, status)
    }

    @Test
    fun `UPLOADED with an empty vault listing (never fetched, keyless, or no-vault) stays UPLOADED`() {
        val status = SessionStatusResolver.resolve(UploadState.UPLOADED, "s1", integratedSessionIds = emptySet())
        assertEquals(SessionStatus.UPLOADED, status)
    }

    @Test
    fun `a vault hit for a LOCAL session id -- e g stale cache after re-recording under the same id -- never fakes INTEGRATED`() {
        val status = SessionStatusResolver.resolve(UploadState.LOCAL, "s1", integratedSessionIds = setOf("s1"))
        assertEquals(SessionStatus.LOCAL, status)
    }
}
