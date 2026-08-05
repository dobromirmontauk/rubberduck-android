package com.montauk.voicecapture.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * [AudioSource] backed by a real [AudioRecord] -- the production capture
 * path, unchanged in behavior from before [AudioSource] existed (bead
 * vn-edu.20 split this class out of [AudioEngine] verbatim).
 *
 * Device selection (e.g. a paired Bluetooth mic) is intentionally not wired
 * up yet -- [AudioRecord] is constructed with the default input device, and
 * [preferredInputDeviceId] is a hook for that follow-up work.
 */
class MicAudioSource(
    override val sampleRateHz: Int = AudioEngine.DEFAULT_SAMPLE_RATE_HZ,
    override val channelCount: Int = 1,
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

    /** Hook for later Bluetooth-mic work; null means "use the system default input". */
    var preferredInputDeviceId: Int? = null

    private var audioRecord: AudioRecord? = null

    @Suppress("MissingPermission") // caller (RecordingService) verifies RECORD_AUDIO before starting
    override fun start() {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            recommendedReadBufferSize * 4,
        )
        preferredInputDeviceId?.let { deviceId ->
            // TODO(bluetooth-mic): resolve deviceId to an AudioDeviceInfo and call
            // record.setPreferredDevice(...). Not wired up yet -- see class KDoc.
            Log.d(TAG, "preferredInputDeviceId=$deviceId requested but routing not implemented yet")
        }
        record.startRecording()
        audioRecord = record
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val record = audioRecord ?: return 0
        val result = record.read(buffer, offset, length)
        return if (result > 0) result else 0
    }

    override fun stop() {
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
    }
}
