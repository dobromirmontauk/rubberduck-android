package com.montauk.voicecapture.session

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdTest {

    @Test
    fun `generate matches the YYYY-MM-DD_HHMM_suffix contract`() {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val fixedNow = format.parse("2026-08-04T23:11:07")!!

        val id = SessionId.generate(now = fixedNow, random = Random(seed = 42))

        assertTrue("expected id to match contract format, got: $id", SessionId.isValid(id))
        assertTrue("expected id to start with the date/time prefix, got: $id", id.startsWith("2026-08-04_2311_"))
    }

    @Test
    fun `generate suffix is four lowercase alphanumeric characters`() {
        val id = SessionId.generate(random = Random(seed = 7))
        val suffix = id.substringAfterLast('_')

        assertEquals(4, suffix.length)
        assertTrue("suffix should be lowercase alnum, got: $suffix", suffix.all { it.isLowerCase() || it.isDigit() })
    }

    @Test
    fun `isValid rejects malformed ids`() {
        assertFalse(SessionId.isValid(""))
        assertFalse(SessionId.isValid("2026-08-04_2311"))
        assertFalse(SessionId.isValid("2026-08-04_2311_AB12")) // uppercase not allowed
        assertFalse(SessionId.isValid("2026-08-04_2311_ab1")) // suffix too short
        assertTrue(SessionId.isValid("2026-08-04_2311_ab12"))
    }
}
