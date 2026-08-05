package com.montauk.voicecapture.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [deviceFlowUserMessage] is the only place a [DeviceFlowPhase.Error] gets
 * turned into UI text -- bead vn-edu.28 traced the app's "weird HTTP 404
 * errors" to [DeviceFlowPhase.Error.message] (raw strings like `"HTTP 404"`)
 * reaching the screen unmapped. Every payload, regardless of what the
 * failure actually was, must map to the same human, actionable sentence.
 */
class DeviceFlowUserMessageTest {

    @Test
    fun `expired maps to a plain-language message`() {
        assertEquals("Code expired", deviceFlowUserMessage(DeviceFlowPhase.Expired))
    }

    @Test
    fun `denied maps to a plain-language message`() {
        assertEquals("Sign-in declined", deviceFlowUserMessage(DeviceFlowPhase.Denied))
    }

    @Test
    fun `an HTTP-code error message never reaches the user verbatim`() {
        val message = deviceFlowUserMessage(DeviceFlowPhase.Error("device code request failed: HTTP 404"))

        assertFalse(message.contains("HTTP"))
        assertFalse(message.contains("404"))
    }

    @Test
    fun `a network exception message never reaches the user verbatim`() {
        val message = deviceFlowUserMessage(DeviceFlowPhase.Error("Unable to resolve host \"github.com\""))

        assertFalse(message.contains("resolve host"))
    }

    @Test
    fun `every error payload maps to the same actionable sentence`() {
        val first = deviceFlowUserMessage(DeviceFlowPhase.Error("HTTP 404"))
        val second = deviceFlowUserMessage(DeviceFlowPhase.Error("HTTP 500"))

        assertEquals(first, second)
    }
}
