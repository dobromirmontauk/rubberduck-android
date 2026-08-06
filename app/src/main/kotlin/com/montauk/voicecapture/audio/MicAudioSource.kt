package com.montauk.voicecapture.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

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
 *
 * Bead asn-b8u hardening (root cause of "connected a Bluetooth headset and
 * recording didn't start at all"): this class's Bluetooth-routing calls used
 * to be able to throw all the way out of [start] with nothing anywhere in
 * the [start]->[com.montauk.voicecapture.audio.AudioEngine.start]->
 * [com.montauk.voicecapture.service.RecordingService.beginRecording] chain
 * to catch it -- an uncaught exception there kills the whole foreground
 * service before `startForeground()` (the only user-visible confirmation
 * recording began) ever runs, which is exactly "nothing started at all" from
 * the user's side. Three independent hardenings below close that off: (1)
 * [AudioRouteSelector] never picks [AudioRouteType.BLUETOOTH_SCO] without
 * [hasBluetoothConnectPermission] (that route's `startBluetoothSco()` call
 * is `@RequiresPermission(BLUETOOTH_CONNECT)` on API 31+ -- minSdk here --
 * and would otherwise throw every time); (2) [currentCandidates] can never
 * throw out of [applyCurrentRoute]/[reroute]; (3) [start] retries once
 * against a plain unrouted [AudioRecord] if the routed one fails to actually
 * start recording, so a Bluetooth-routing failure specifically can never by
 * itself prevent a session from starting. [context] additionally lets this
 * class watch for the Bluetooth SCO link actually coming up (see
 * [watchForScoConnect]) with a bounded timeout rather than optimistically
 * treating `startBluetoothSco()` returning as "the audio is flowing now" --
 * null in the same test contexts [audioManager] is null in, which simply
 * skips that watch (see [watchForScoConnect]'s early return).
 */
class MicAudioSource(
    override val sampleRateHz: Int = AudioEngine.DEFAULT_SAMPLE_RATE_HZ,
    override val channelCount: Int = 1,
    private val audioManager: AudioManager? = null,
    private val context: Context? = null,
    private val hasBluetoothConnectPermission: () -> Boolean = { true },
    private val scoConnectTimeoutMs: Long = DEFAULT_SCO_CONNECT_TIMEOUT_MS,
) : AudioSource {

    companion object {
        private const val TAG = "MicAudioSource"

        /**
         * Generous relative to how long a healthy SCO negotiation actually
         * takes (typically well under a second) but short enough that a
         * dead/out-of-range/mid-negotiation-failure headset falls back to
         * the phone mic within a few seconds rather than recording from an
         * unconfirmed route (silently, for the rest of the session) -- see
         * [watchForScoConnect].
         */
        const val DEFAULT_SCO_CONNECT_TIMEOUT_MS = 3_000L
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
     * Bead asn-b8u: device ids whose SCO link either errored/disconnected or
     * never confirmed [AudioManager.SCO_AUDIO_STATE_CONNECTED] within
     * [scoConnectTimeoutMs] -- see [watchForScoConnect]/[onScoOutcome].
     * [toRouteCandidate] treats a device in here as though it were absent,
     * so [AudioRouteSelector] falls through to the next candidate (or the
     * builtin mic) instead of repeatedly re-selecting a route that's already
     * demonstrated it doesn't actually deliver audio. Never removed once
     * added -- a device only leaves this set by disconnecting and
     * reconnecting, which [toRouteCandidate] sees as a genuinely new
     * candidate on the next [AudioDeviceCallback] callback.
     */
    private val failedScoDeviceIds = mutableSetOf<Int>()

    private val scoHandler = Handler(Looper.getMainLooper())
    private var pendingScoWatch: ScoWatch? = null

    private class ScoWatch(val deviceId: Int, val receiver: BroadcastReceiver, val timeoutRunnable: Runnable)

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
        val record = newAudioRecord()
        audioRecord = record
        applyCurrentRoute(record)
        startRecordingWithFallback(record)
    }

    /**
     * Bead asn-b8u root-cause fix: [AudioRecord.startRecording] can throw
     * `IllegalStateException` when the platform has already steered this
     * [AudioRecord] at a Bluetooth route whose link isn't actually up yet.
     * Before this fix, that exception propagated all the way out of [start]
     * with nothing in the call chain up to
     * [com.montauk.voicecapture.service.RecordingService.beginRecording] to
     * catch it, killing the whole foreground service before
     * `startForeground()` ever ran -- "connected a Bluetooth headset and
     * recording didn't start at all" from the user's side. Retrying once
     * against a brand-new, never-routed [AudioRecord] (no
     * `setPreferredDevice` call at all, exactly this class's pre-vn-edu.2
     * behavior) means a Bluetooth-routing failure specifically can never by
     * itself block a session from starting; a failure on *that* second
     * attempt is a real microphone/permission problem, not a Bluetooth one,
     * and is allowed to propagate since there's nothing left to fall back to.
     */
    private fun startRecordingWithFallback(record: AudioRecord) {
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed on the Bluetooth-routed AudioRecord; retrying against the plain default input", e)
            runCatching { record.release() }
            cancelPendingScoWatch()
            val fallback = newAudioRecord()
            audioRecord = fallback
            fallback.startRecording()
            onRouteChanged?.invoke(
                RouteTransition(
                    AudioRoute(AudioRouteType.BUILTIN_MIC, deviceId = null, needsBluetoothSco = false, scoNarrowbandWarning = false),
                    isDeviceLossFallback = false,
                ),
            )
            registerDeviceCallback(fallback)
            return
        }
        registerDeviceCallback(record)
    }

    private fun newAudioRecord(): AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRateHz,
        channelConfig,
        AudioFormat.ENCODING_PCM_16BIT,
        recommendedReadBufferSize * 4,
    )

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val record = audioRecord ?: return 0
        val result = record.read(buffer, offset, length)
        return if (result > 0) result else 0
    }

    override fun stop() {
        deviceCallback?.let { callback -> runCatching { audioManager?.unregisterAudioDeviceCallback(callback) } }
        deviceCallback = null
        cancelPendingScoWatch()
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
        val transition = routeController.onDevicesChanged(currentCandidates(manager), hasBluetoothConnectPermission()) ?: return
        applyTransition(record, manager, transition)
    }

    private fun applyTransition(record: AudioRecord, manager: AudioManager, transition: RouteTransition) {
        val route = transition.route
        cancelPendingScoWatch()
        // startBluetoothSco/stopBluetoothSco are ref-counted-ish but tolerate
        // redundant calls fine; calling stop unconditionally on a non-SCO
        // route is simpler and safer than tracking "did we start it" separately.
        if (route.needsBluetoothSco) {
            val started = runCatching { manager.startBluetoothSco() }.isSuccess
            if (started) watchForScoConnect(manager, route)
        } else {
            runCatching { manager.stopBluetoothSco() }
        }
        val targetDevice = route.deviceId?.let { id ->
            runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id } }.getOrNull()
        }
        runCatching { record.setPreferredDevice(targetDevice) }
            .onFailure { e -> Log.w(TAG, "setPreferredDevice failed for route ${route.type}", e) }
        onRouteChanged?.invoke(transition)
    }

    /**
     * Bead asn-b8u: `AudioManager.startBluetoothSco()` only *requests* the
     * link -- the actual negotiation completes (or fails) asynchronously,
     * signalled by the [AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED]
     * broadcast. Before this fix nothing waited for or checked that signal
     * at all: [applyTransition] fired the request and moved on as if the
     * link were already live, so a headset that failed to actually connect
     * (out of range, powered off mid-negotiation, etc.) recorded silently
     * from an unconfirmed route -- with the UI still showing a plain
     * BLUETOOTH chip -- for the rest of the session. This registers a
     * one-shot receiver plus a [scoConnectTimeoutMs] timeout; whichever
     * resolves first marks [failedScoDeviceIds] and reroutes on anything
     * other than a real CONNECTED confirmation, via [onScoOutcome]. No-op
     * when [context] or [audioManager] is null (see class KDoc).
     */
    private fun watchForScoConnect(manager: AudioManager, route: AudioRoute) {
        val ctx = context ?: return
        val deviceId = route.deviceId ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    onScoOutcome(manager, deviceId, connected = true)
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED || state == AudioManager.SCO_AUDIO_STATE_ERROR) {
                    onScoOutcome(manager, deviceId, connected = false)
                }
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        if (!registered) return
        val timeoutRunnable = Runnable { onScoOutcome(manager, deviceId, connected = false) }
        scoHandler.postDelayed(timeoutRunnable, scoConnectTimeoutMs)
        pendingScoWatch = ScoWatch(deviceId, receiver, timeoutRunnable)
    }

    /**
     * Resolves the one outstanding [pendingScoWatch], if it's still for
     * [deviceId] (a stale receiver/timeout callback that lost the race
     * against a newer route change is simply ignored). On anything but a
     * real connect, marks the device permanently failed for this session and
     * re-evaluates the route -- which, via [toRouteCandidate] excluding
     * [failedScoDeviceIds], falls through to the next candidate or the
     * builtin mic and (since the previous route was Bluetooth) comes back
     * from [AudioRouteController] as a device-loss fallback, reusing the
     * exact same `bluetoothDeviceLost`/"BT LOST -> PHONE MIC" UI path
     * mid-session loss already uses -- no new UI state needed for "SCO never
     * came up" vs. "SCO came up then dropped", they read the same to the user.
     */
    private fun onScoOutcome(manager: AudioManager, deviceId: Int, connected: Boolean) {
        val watch = pendingScoWatch ?: return
        if (watch.deviceId != deviceId) return
        cancelPendingScoWatch()
        if (connected) return
        Log.w(TAG, "Bluetooth SCO device $deviceId never confirmed connected within ${scoConnectTimeoutMs}ms; falling back to the phone mic")
        failedScoDeviceIds += deviceId
        val record = audioRecord ?: return
        reroute(record, manager)
    }

    private fun cancelPendingScoWatch() {
        val watch = pendingScoWatch ?: return
        pendingScoWatch = null
        scoHandler.removeCallbacks(watch.timeoutRunnable)
        runCatching { context?.unregisterReceiver(watch.receiver) }
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
        val transition = routeController.onDevicesChanged(currentCandidates(manager), hasBluetoothConnectPermission()) ?: return
        applyTransition(record, manager, transition)
    }

    /**
     * Bead asn-b8u: wrapped in [runCatching] -- unlike the calls in
     * [applyTransition], which each guard themselves individually,
     * `AudioManager.getDevices` here had no guard at all, so any exception
     * from it (permission-related or otherwise) used to propagate straight
     * out of [applyCurrentRoute]/[reroute] and up through [start] uncaught.
     * Falling back to "no candidates" degrades to [AudioRouteType.BUILTIN_MIC]
     * via the normal [AudioRouteSelector] path -- the same outcome as if no
     * Bluetooth device were actually present.
     */
    private fun currentCandidates(manager: AudioManager): List<RouteCandidate> =
        runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { toRouteCandidate(it) } }
            .getOrElse { e ->
                Log.w(TAG, "Failed to enumerate input devices; treating as builtin-mic-only for this evaluation", e)
                emptyList()
            }

    /**
     * `AudioDeviceInfo.TYPE_BLE_HEADSET` only exists from API 33 (Tiramisu)
     * -- this app's minSdk is 31, so devices are never classified as BLE on
     * an API 31/32 device and this policy naturally falls back to SCO/builtin
     * there instead of crashing on a missing constant.
     *
     * A [AudioDeviceInfo.TYPE_BLUETOOTH_SCO] device whose id is in
     * [failedScoDeviceIds] (bead asn-b8u: its SCO link already
     * errored/disconnected or never confirmed within [scoConnectTimeoutMs]
     * this session) is reported as absent -- see [failedScoDeviceIds]'s KDoc.
     */
    private fun toRouteCandidate(info: AudioDeviceInfo): RouteCandidate? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET ->
            RouteCandidate(info.id, AudioRouteType.BLE_HEADSET)
        info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && info.id !in failedScoDeviceIds ->
            RouteCandidate(info.id, AudioRouteType.BLUETOOTH_SCO)
        else -> null
    }
}
