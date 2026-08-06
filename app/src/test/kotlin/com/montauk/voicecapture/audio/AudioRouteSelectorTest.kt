package com.montauk.voicecapture.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteSelectorTest {

    @Test
    fun `no bluetooth devices available falls back to the builtin mic`() {
        val route = AudioRouteSelector.selectRoute(emptyList())

        assertEquals(AudioRouteType.BUILTIN_MIC, route.type)
        assertNull(route.deviceId)
        assertFalse(route.needsBluetoothSco)
        assertFalse(route.scoNarrowbandWarning)
    }

    @Test
    fun `prefers BLE headset over SCO when both are present`() {
        val devices = listOf(
            RouteCandidate(deviceId = 1, type = AudioRouteType.BLUETOOTH_SCO),
            RouteCandidate(deviceId = 2, type = AudioRouteType.BLE_HEADSET),
        )

        val route = AudioRouteSelector.selectRoute(devices)

        assertEquals(AudioRouteType.BLE_HEADSET, route.type)
        assertEquals(2, route.deviceId)
        assertFalse("LE Audio is wideband -- must never carry the narrowband warning", route.scoNarrowbandWarning)
        assertFalse(route.needsBluetoothSco)
    }

    @Test
    fun `falls back to SCO when no BLE headset is present`() {
        val devices = listOf(RouteCandidate(deviceId = 5, type = AudioRouteType.BLUETOOTH_SCO))

        val route = AudioRouteSelector.selectRoute(devices)

        assertEquals(AudioRouteType.BLUETOOTH_SCO, route.type)
        assertEquals(5, route.deviceId)
        assertTrue("classic SCO must start the SCO link explicitly", route.needsBluetoothSco)
        assertTrue("classic SCO is treated as narrowband (8kHz) and must warn", route.scoNarrowbandWarning)
    }

    @Test
    fun `prefers builtin mic over neither being present is not possible -- builtin is always the last resort`() {
        val devices = listOf(RouteCandidate(deviceId = 9, type = AudioRouteType.BLE_HEADSET))

        val route = AudioRouteSelector.selectRoute(devices)

        assertEquals(AudioRouteType.BLE_HEADSET, route.type)
    }

    @Test
    fun `picks the first BLE headset when multiple are somehow present`() {
        val devices = listOf(
            RouteCandidate(deviceId = 11, type = AudioRouteType.BLE_HEADSET),
            RouteCandidate(deviceId = 12, type = AudioRouteType.BLE_HEADSET),
        )

        val route = AudioRouteSelector.selectRoute(devices)

        assertEquals(11, route.deviceId)
    }

    // Bead asn-b8u: AudioManager#startBluetoothSco() requires BLUETOOTH_CONNECT
    // on API 31+ (this app's minSdk) -- without the permission that call throws
    // every time, so selecting BLUETOOTH_SCO without it produces a chip that
    // says "BLUETOOTH" over a link that never actually carries audio. These
    // cases pin down that the gate applies ONLY to the SCO route.

    @Test
    fun `without BLUETOOTH_CONNECT, a classic SCO device is treated as though it were absent`() {
        val devices = listOf(RouteCandidate(deviceId = 5, type = AudioRouteType.BLUETOOTH_SCO))

        val route = AudioRouteSelector.selectRoute(devices, bluetoothConnectGranted = false)

        assertEquals(AudioRouteType.BUILTIN_MIC, route.type)
        assertNull(route.deviceId)
        assertFalse(route.needsBluetoothSco)
    }

    @Test
    fun `without BLUETOOTH_CONNECT, a BLE headset is still preferred -- that route never calls startBluetoothSco`() {
        val devices = listOf(RouteCandidate(deviceId = 2, type = AudioRouteType.BLE_HEADSET))

        val route = AudioRouteSelector.selectRoute(devices, bluetoothConnectGranted = false)

        assertEquals(AudioRouteType.BLE_HEADSET, route.type)
        assertEquals(2, route.deviceId)
    }

    @Test
    fun `without BLUETOOTH_CONNECT, BLE still wins over SCO when both are present`() {
        val devices = listOf(
            RouteCandidate(deviceId = 1, type = AudioRouteType.BLUETOOTH_SCO),
            RouteCandidate(deviceId = 2, type = AudioRouteType.BLE_HEADSET),
        )

        val route = AudioRouteSelector.selectRoute(devices, bluetoothConnectGranted = false)

        assertEquals(AudioRouteType.BLE_HEADSET, route.type)
    }

    @Test
    fun `bluetoothConnectGranted defaults to true, matching pre-asn-b8u behavior`() {
        val devices = listOf(RouteCandidate(deviceId = 5, type = AudioRouteType.BLUETOOTH_SCO))

        val route = AudioRouteSelector.selectRoute(devices)

        assertEquals(AudioRouteType.BLUETOOTH_SCO, route.type)
    }
}

class AudioRouteControllerTest {

    private val ble = RouteCandidate(deviceId = 1, type = AudioRouteType.BLE_HEADSET)
    private val sco = RouteCandidate(deviceId = 2, type = AudioRouteType.BLUETOOTH_SCO)

    @Test
    fun `initial route pick is never flagged as a device loss`() {
        val controller = AudioRouteController()

        val transition = controller.onDevicesChanged(listOf(ble))

        requireNotNull(transition)
        assertEquals(AudioRouteType.BLE_HEADSET, transition.route.type)
        assertFalse("starting a session on Bluetooth is not a 'loss'", transition.isDeviceLossFallback)
    }

    @Test
    fun `starting with no bluetooth device present is also not a device loss`() {
        val controller = AudioRouteController()

        val transition = controller.onDevicesChanged(emptyList())

        requireNotNull(transition)
        assertEquals(AudioRouteType.BUILTIN_MIC, transition.route.type)
        assertFalse(transition.isDeviceLossFallback)
    }

    @Test
    fun `losing the BLE headset mid-session falls back to the builtin mic and is flagged as a device loss`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(ble))

        val transition = controller.onDevicesChanged(emptyList())

        requireNotNull(transition)
        assertEquals(AudioRouteType.BUILTIN_MIC, transition.route.type)
        assertNull(transition.route.deviceId)
        assertTrue("BLE headset disappearing mid-session must be flagged as a loss", transition.isDeviceLossFallback)
    }

    @Test
    fun `losing the SCO device mid-session also falls back and is flagged`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(sco))

        val transition = controller.onDevicesChanged(emptyList())

        requireNotNull(transition)
        assertEquals(AudioRouteType.BUILTIN_MIC, transition.route.type)
        assertTrue(transition.isDeviceLossFallback)
    }

    @Test
    fun `downgrading from BLE to SCO -- not all the way to builtin -- is a route change but not a device loss`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(ble, sco))

        val transition = controller.onDevicesChanged(listOf(sco))

        requireNotNull(transition)
        assertEquals(AudioRouteType.BLUETOOTH_SCO, transition.route.type)
        assertFalse("still on Bluetooth, just a different device -- not a fallback to the phone mic", transition.isDeviceLossFallback)
    }

    @Test
    fun `an unchanged device list produces no transition at all`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(ble))

        val transition = controller.onDevicesChanged(listOf(ble))

        assertNull("no-op re-evaluation must not re-trigger routing calls or UI updates", transition)
    }

    @Test
    fun `reconnecting a BLE headset after a device-loss fallback upgrades back and is not itself a loss`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(ble))
        controller.onDevicesChanged(emptyList()) // device loss

        val transition = controller.onDevicesChanged(listOf(ble))

        requireNotNull(transition)
        assertEquals(AudioRouteType.BLE_HEADSET, transition.route.type)
        assertFalse(transition.isDeviceLossFallback)
    }

    @Test
    fun `currentRoute reflects the most recent decision even when no transition was returned`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(ble))

        controller.onDevicesChanged(listOf(ble)) // no-op

        assertEquals(AudioRouteType.BLE_HEADSET, controller.currentRoute?.type)
    }

    // Bead asn-b8u: onDevicesChanged forwards bluetoothConnectGranted straight
    // through to AudioRouteSelector -- these pin the plumbing, not the policy
    // itself (AudioRouteSelectorTest already covers the policy in depth).

    @Test
    fun `revoking BLUETOOTH_CONNECT mid-session falls back from SCO to the builtin mic and is flagged as a device loss`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(sco), bluetoothConnectGranted = true)

        val transition = controller.onDevicesChanged(listOf(sco), bluetoothConnectGranted = false)

        requireNotNull(transition)
        assertEquals(AudioRouteType.BUILTIN_MIC, transition.route.type)
        assertTrue("losing the permission is indistinguishable from losing the device -- both fall back", transition.isDeviceLossFallback)
    }

    @Test
    fun `granting BLUETOOTH_CONNECT mid-session upgrades a still-present SCO device back`() {
        val controller = AudioRouteController()
        controller.onDevicesChanged(listOf(sco), bluetoothConnectGranted = false)

        val transition = controller.onDevicesChanged(listOf(sco), bluetoothConnectGranted = true)

        requireNotNull(transition)
        assertEquals(AudioRouteType.BLUETOOTH_SCO, transition.route.type)
        assertFalse(transition.isDeviceLossFallback)
    }
}
