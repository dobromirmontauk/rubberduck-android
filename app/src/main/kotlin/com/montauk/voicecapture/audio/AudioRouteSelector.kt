package com.montauk.voicecapture.audio

/** Which physical class of input device is providing audio right now. */
enum class AudioRouteType { BLE_HEADSET, BLUETOOTH_SCO, BUILTIN_MIC }

/**
 * Android-framework-free view of one candidate input device -- deliberately
 * not `android.media.AudioDeviceInfo` itself (that class has no public
 * constructor and is effectively impossible to instantiate in a plain JVM or
 * Robolectric test), so [AudioRouteSelector] can be unit tested without any
 * real or shadowed Bluetooth hardware. [MicAudioSource] is the only place
 * that translates real `AudioDeviceInfo` values into these.
 */
data class RouteCandidate(val deviceId: Int, val type: AudioRouteType)

/**
 * Immutable result of a routing decision. [deviceId] is null for
 * [AudioRouteType.BUILTIN_MIC] -- there's nothing to pass to
 * `AudioRecord.setPreferredDevice`; null there means "use the system
 * default input".
 */
data class AudioRoute(
    val type: AudioRouteType,
    val deviceId: Int?,
    /** True only for [AudioRouteType.BLUETOOTH_SCO] -- caller must start the SCO link via `AudioManager.startBluetoothSco()` before routing to it. */
    val needsBluetoothSco: Boolean,
    /** True only for [AudioRouteType.BLUETOOTH_SCO] -- see [AudioRouteSelector]'s KDoc for why classic SCO is flagged and LE Audio isn't. */
    val scoNarrowbandWarning: Boolean,
)

/**
 * Pure decision policy for bead vn-edu.2 (LE Audio routing + SCO fallback +
 * device-loss recovery): given the set of currently available Bluetooth
 * input devices, decides which one audio capture should prefer. Takes plain
 * [RouteCandidate] values rather than `android.media.AudioDeviceInfo` so the
 * whole policy is unit-testable on the JVM -- [MicAudioSource] is the only
 * caller that touches the real Android APIs, translating `AudioDeviceInfo`
 * into [RouteCandidate] going in and an [AudioRoute] decision into
 * `setPreferredDevice`/`startBluetoothSco` calls going out.
 *
 * Preference order, per the bead spec: [AudioRouteType.BLE_HEADSET] (LE
 * Audio, wideband) > [AudioRouteType.BLUETOOTH_SCO] (classic HFP) >
 * [AudioRouteType.BUILTIN_MIC]. Classic Bluetooth SCO links almost always
 * negotiate the narrowband (~8kHz) CVSD codec rather than wideband mSBC --
 * `AudioDeviceInfo` exposes no per-call codec/sample-rate detail to check
 * this precisely, so this policy treats "the chosen route is
 * [AudioRouteType.BLUETOOTH_SCO] at all" as the 8kHz-narrowband signal and
 * flags [AudioRoute.scoNarrowbandWarning] accordingly; LE Audio never gets
 * this warning since it's wideband by design.
 */
object AudioRouteSelector {
    fun selectRoute(devices: List<RouteCandidate>): AudioRoute {
        devices.firstOrNull { it.type == AudioRouteType.BLE_HEADSET }?.let { ble ->
            return AudioRoute(AudioRouteType.BLE_HEADSET, ble.deviceId, needsBluetoothSco = false, scoNarrowbandWarning = false)
        }
        devices.firstOrNull { it.type == AudioRouteType.BLUETOOTH_SCO }?.let { sco ->
            return AudioRoute(AudioRouteType.BLUETOOTH_SCO, sco.deviceId, needsBluetoothSco = true, scoNarrowbandWarning = true)
        }
        return AudioRoute(AudioRouteType.BUILTIN_MIC, deviceId = null, needsBluetoothSco = false, scoNarrowbandWarning = false)
    }
}

/** What changed as a result of an [AudioRouteController.onDevicesChanged] call. */
data class RouteTransition(
    val route: AudioRoute,
    /**
     * True when this transition is a mid-session Bluetooth device loss --
     * the previous route was Bluetooth (either kind) and the new one fell
     * all the way back to [AudioRouteType.BUILTIN_MIC]. False for the
     * initial route pick at recording start (there's no previous device to
     * lose) and false for a lateral change (e.g. SCO -> BLE upgrade).
     */
    val isDeviceLossFallback: Boolean,
)

/**
 * Stateful wrapper around [AudioRouteSelector] that remembers the
 * currently-applied route so [MicAudioSource] only re-applies routing calls
 * -- and only reports a change to the UI -- when the decision actually
 * differs from before. Bead vn-edu.2's device-loss requirement ("mid-session
 * device loss falls back to phone mic ... emit a state change the UI can
 * show") reduces to: recompute [AudioRouteSelector.selectRoute] against the
 * latest device list every time `AudioDeviceCallback` fires, and treat a
 * Bluetooth -> builtin-mic drop as the loss case (see
 * [RouteTransition.isDeviceLossFallback]).
 *
 * Deliberately holds no reference to `AudioRecord`/`AudioManager`/the WAL --
 * recomputing a route here never touches capture at all. The WAL-continuity
 * guarantee in the bead ("never a truncated/corrupt WAL") comes from
 * [MicAudioSource] applying a route change via `AudioRecord.setPreferredDevice`
 * on the *same, still-running* `AudioRecord` instead of stopping and
 * restarting capture -- this class only decides *what* to apply, never
 * *when* to tear anything down.
 */
class AudioRouteController {
    var currentRoute: AudioRoute? = null
        private set

    /**
     * Recomputes the route from [devices] and updates [currentRoute].
     * Returns null when the decision is unchanged (caller should do
     * nothing -- no redundant `setPreferredDevice` calls, no redundant UI
     * update); otherwise returns the new route plus whether this transition
     * is a Bluetooth device-loss fallback.
     */
    fun onDevicesChanged(devices: List<RouteCandidate>): RouteTransition? {
        val previous = currentRoute
        val next = AudioRouteSelector.selectRoute(devices)
        currentRoute = next
        if (previous == next) return null
        val wasBluetooth = previous != null && previous.type != AudioRouteType.BUILTIN_MIC
        val isDeviceLossFallback = wasBluetooth && next.type == AudioRouteType.BUILTIN_MIC
        return RouteTransition(next, isDeviceLossFallback)
    }
}
