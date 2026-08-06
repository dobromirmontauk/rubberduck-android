package com.montauk.voicecapture.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [DetailTabPlaceholder.forStatus]'s placeholder-state derivation matrix (bead vn-edu.57) -- pure, no I/O. */
class DetailTabPlaceholderTest {

    @Test
    fun `LOCAL shows Not uploaded yet`() {
        assertEquals(DetailTabPlaceholder.NOT_UPLOADED, DetailTabPlaceholder.forStatus(SessionStatus.LOCAL))
    }

    @Test
    fun `QUEUED shows Not uploaded yet`() {
        assertEquals(DetailTabPlaceholder.NOT_UPLOADED, DetailTabPlaceholder.forStatus(SessionStatus.QUEUED))
    }

    @Test
    fun `UPLOADED shows Awaiting organization`() {
        assertEquals(DetailTabPlaceholder.AWAITING_ORGANIZATION, DetailTabPlaceholder.forStatus(SessionStatus.UPLOADED))
    }

    @Test
    fun `INTEGRATED has no placeholder -- caller should fetch instead`() {
        assertNull(DetailTabPlaceholder.forStatus(SessionStatus.INTEGRATED))
    }
}
