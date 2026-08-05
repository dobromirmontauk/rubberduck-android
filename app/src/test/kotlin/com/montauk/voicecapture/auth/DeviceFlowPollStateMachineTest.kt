package com.montauk.voicecapture.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure transition logic for the device-flow poll loop -- no HTTP, no
 * coroutines. [GitHubDeviceFlowClientTest] covers the HTTP classification
 * that produces these [DevicePollOutcome]s; this covers what the state
 * machine does with them.
 */
class DeviceFlowPollStateMachineTest {

    @Test
    fun `pending keeps polling at the same interval`() {
        val machine = DeviceFlowPollStateMachine(initialIntervalSeconds = 5)

        val phase = machine.apply(DevicePollOutcome.Pending)

        assertEquals(DeviceFlowPhase.Idle, phase)
        assertEquals(5, machine.intervalSeconds)
    }

    @Test
    fun `slow_down grows the interval by 5 seconds and keeps polling`() {
        val machine = DeviceFlowPollStateMachine(initialIntervalSeconds = 5)

        val first = machine.apply(DevicePollOutcome.SlowDown)
        val second = machine.apply(DevicePollOutcome.SlowDown)

        assertEquals(DeviceFlowPhase.Idle, first)
        assertEquals(DeviceFlowPhase.Idle, second)
        assertEquals(15, machine.intervalSeconds)
    }

    @Test
    fun `success surfaces the access token and stops polling`() {
        val machine = DeviceFlowPollStateMachine(initialIntervalSeconds = 5)

        val phase = machine.apply(DevicePollOutcome.Success("gho_faketoken"))

        assertTrue(phase is DeviceFlowPhase.Success)
        assertEquals("gho_faketoken", (phase as DeviceFlowPhase.Success).accessToken)
    }

    @Test
    fun `expired token maps to the Expired terminal phase`() {
        val machine = DeviceFlowPollStateMachine(initialIntervalSeconds = 5)

        assertEquals(DeviceFlowPhase.Expired, machine.apply(DevicePollOutcome.ExpiredToken))
    }

    @Test
    fun `access denied maps to the Denied terminal phase`() {
        val machine = DeviceFlowPollStateMachine(initialIntervalSeconds = 5)

        assertEquals(DeviceFlowPhase.Denied, machine.apply(DevicePollOutcome.AccessDenied))
    }

    @Test
    fun `errors carry their message through to the UI phase`() {
        val machine = DeviceFlowPollStateMachine(initialIntervalSeconds = 5)

        val phase = machine.apply(DevicePollOutcome.Error("HTTP 500"))

        assertEquals(DeviceFlowPhase.Error("HTTP 500"), phase)
    }
}
