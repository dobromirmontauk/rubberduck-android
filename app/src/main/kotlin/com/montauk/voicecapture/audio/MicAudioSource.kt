package com.montauk.voicecapture.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * [AudioSource] backed by a real [AudioRecord] -- the production capture
 * path (bead vn-edu.20 split this class out of [AudioEngine] verbatim; bead
 * vn-edu.2 added the Bluetooth routing below).
 *
 * Device selection prefers LE Audio ([AudioDeviceInfo.TYPE_BLE_HEADSET])
 * over classic Bluetooth SCO over the builtin mic -- see
 * [AudioRouteSelector]'s KDoc for the policy itself, which is deliberately
 * pulled out into a plain, Android-framework-free class so it's unit
 * testable. This class only ever calls `AudioRecord.setPreferredDevice` on
 * the *same* running [AudioRecord] instance to change routes, both at
 * [start] and again whenever [AudioDeviceCallback] reports a device
 * add/remove -- never stopping/restarting capture -- which is what lets a
 * mid-session Bluetooth device loss fall back to the phone mic without
 * losing or truncating anything already written to the WAL: from
 * [AudioEngine]'s point of view, this source's [read] loop never stops.
 *
 * [audioManager] is null in AudioManager-less test contexts (unit tests that
 * only touch [deviceLabel] without ever calling [start]); when null, routing
 * is a no-op and [AudioRecord] simply uses the system default input, exactly
 * like this class's pre-vn-edu.2 behavior.
 */
class MicAudioSource(
    override val sampleRateHz: Int = AudioEngine.DEFAULT_SAMPLE_RATE_HZ,
    override val channelCount: Int = 1,
    private val audioManager: AudioManager? = null,
) : AudioSource {

    companion object {
        private const val TAG = "MicAudioSource"
    }

    override val deviceLabel: String = "MIC"

    private val channelConfig =
        if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    override val recommendedReadBufferSize: Int by lazy {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        require(minBufferSize > 0) { "Unable to size AudioRecord buffer for this device" }
        minBufferSize
    }

    private val routeController = AudioRouteController()
    private var deviceCallback: AudioDeviceCallback? = null
    private var audioRecord: AudioRecord? = null

    /**
     * Fired whenever [routeController] reports a real transition -- the
     * initial pick at [start], a Bluetooth device upgrading/downgrading to
     * another Bluetooth device, or a device-loss fallback to the builtin
     * mic. [RecordingService] wires this into [com.montauk.voicecapture.service.RecordingStateHolder]
     * so the recording screen can show the current route plus the
     * SCO-narrowband and device-loss badges. Called on whichever thread the
     * triggering event arrived on ([start]'s caller thread, or the main
     * thread for [AudioDeviceCallback] -- never the capture thread), so
     * callers must not assume any particular thread.
     */
    var onRouteChanged: ((RouteTransition) -> Unit)? = null

    @Suppress("MissingPermission") // caller (RecordingService) verifies RECORD_AUDIO before starting
    override fun start() {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            recommendedReadBufferSize * 4,
        )
        audioRecord = record
        applyCurrentRoute(record)
        record.startRecording()
        registerDeviceCallback(record)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val record = audioRecord ?: return 0
        val result = record.read(buffer, offset, length)
        return if (result > 0) result else 0
    }

    override fun stop() {
        deviceCallback?.let { callback -> runCatching { audioManager?.unregisterAudioDeviceCallback(callback) } }
        deviceCallback = null
        if (routeController.currentRoute?.needsBluetoothSco == true) {
            runCatching { audioManager?.stopBluetoothSco() }
        }
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
    }

    /** Recomputes the route from [audioManager]'s current input device list and, if it changed, applies + reports it. */
    private fun applyCurrentRoute(record: AudioRecord) {
        val manager = audioManager ?: return
        val transition = routeController.onDevicesChanged(currentCandidates(manager)) ?: return
        applyTransition(record, manager, transition)
    }

    private fun applyTransition(record: AudioRecord, manager: AudioManager, transition: RouteTransition) {
        val route = transition.route
        // startBluetoothSco/stopBluetoothSco are ref-counted-ish but tolerate
        // redundant calls fine; calling stop unconditionally on a non-SCO
        // route is simpler and safer than tracking "did we start it" separately.
        if (route.needsBluetoothSco) {
            runCatching { manager.startBluetoothSco() }
        } else {
            runCatching { manager.stopBluetoothSco() }
        }
        val targetDevice = route.deviceId?.let { id -> manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id } }
        runCatching { record.setPreferredDevice(targetDevice) }
            .onFailure { e -> Log.w(TAG, "setPreferredDevice failed for route ${route.type}", e) }
        onRouteChanged?.invoke(transition)
    }

    /**
     * Registers for add/remove notifications so a mid-session Bluetooth
     * device loss (or reconnect) re-runs [AudioRouteSelector] against the
     * fresh device list -- see class KDoc for why this never stops [record].
     */
    private fun registerDeviceCallback(record: AudioRecord) {
        val manager = audioManager ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute(record, manager)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reroute(record, manager)
        }
        manager.registerAudioDeviceCallback(callback, null)
        deviceCallback = callback
    }

    private fun reroute(record: AudioRecord, manager: AudioManager) {
        val transition = routeController.onDevicesChanged(currentCandidates(manager)) ?: return
        applyTransition(record, manager, transition)
    }

    private fun currentCandidates(manager: AudioManager): List<RouteCandidate> =
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { toRouteCandidate(it) }

    /**
     * `AudioDeviceInfo.TYPE_BLE_HEADSET` only exists from API 33 (Tiramisu)
     * -- this app's minSdk is 31, so devices are never classified as BLE on
     * an API 31/32 device and this policy naturally falls back to SCO/builtin
     * there instead of crashing on a missing constant.
     */
    private fun toRouteCandidate(info: AudioDeviceInfo): RouteCandidate? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET ->
            RouteCandidate(info.id, AudioRouteType.BLE_HEADSET)
        info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> RouteCandidate(info.id, AudioRouteType.BLUETOOTH_SCO)
        else -> null
    }
}
