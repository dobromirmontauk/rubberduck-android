package com.montauk.voicecapture.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * The recording core: AudioRecord -> MediaCodec Opus encoder -> [OpusFrameWal].
 *
 * Capture runs on its own dedicated thread so it is never blocked by UI, STT
 * streaming, or upload work. The WAL write in [OpusFrameWal.Writer.append] is
 * the crash-safety boundary described in docs/audio-wal.md: every call to
 * [onEncodedFrame] durably persists before the next chunk of PCM is even read.
 *
 * Device selection (e.g. a paired Bluetooth mic) is intentionally not wired up
 * yet -- [AudioRecord] is constructed with the default input device, and
 * [preferredInputDeviceId] is a hook for that follow-up work.
 */
class AudioEngine(
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val channelCount: Int = 1,
    private val bitRate: Int = DEFAULT_BIT_RATE,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_BIT_RATE = 32_000
        private const val TAG = "AudioEngine"
        private const val TIMEOUT_US = 10_000L
        private const val STOP_JOIN_TIMEOUT_MS = 5_000L
    }

    /** Hook for later Bluetooth-mic work; null means "use the system default input". */
    var preferredInputDeviceId: Int? = null

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var walWriter: OpusFrameWal.Writer? = null
    private var captureThread: Thread? = null
    @Volatile private var recording = false

    private val channelConfig =
        if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    /**
     * Starts capturing into [walFile]. Returns once the encoder and recorder
     * are both running; encoding happens on a background thread until [stop]
     * is called.
     */
    @Suppress("MissingPermission") // caller (RecordingService) verifies RECORD_AUDIO before starting
    fun start(walFile: File) {
        check(!recording) { "AudioEngine already recording" }

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBufferSize > 0) { "Unable to size AudioRecord buffer for this device" }

        val record = AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4,
        )
        preferredInputDeviceId?.let { deviceId ->
            // TODO(bluetooth-mic): resolve deviceId to an AudioDeviceInfo and call
            // record.setPreferredDevice(...). Not wired up yet -- see class KDoc.
            Log.d(TAG, "preferredInputDeviceId=$deviceId requested but routing not implemented yet")
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        walWriter = OpusFrameWal.Writer(walFile, OpusFrameWal.Header(sampleRateHz, channelCount))
        audioRecord = record
        encoder = codec

        record.startRecording()
        codec.start()
        recording = true

        captureThread = thread(name = "audio-engine-capture") {
            runCaptureLoop(record, codec, minBufferSize)
        }
    }

    private fun runCaptureLoop(record: AudioRecord, codec: MediaCodec, minBufferSize: Int) {
        val pcmBuffer = ByteArray(minBufferSize)
        try {
            while (recording) {
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead > 0) {
                    feedInput(codec, pcmBuffer, bytesRead)
                }
                drainOutput(codec, endOfStream = false)
            }
            // Recording stopped: flush remaining PCM through the encoder and drain it fully.
            feedEndOfStream(codec)
            drainOutput(codec, endOfStream = true)
        } catch (t: Throwable) {
            Log.e(TAG, "capture loop failed", t)
        }
    }

    private fun feedInput(codec: MediaCodec, pcm: ByteArray, length: Int) {
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex < 0) return
        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(pcm, 0, length)
        val presentationTimeUs = System.nanoTime() / 1000
        codec.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
    }

    private fun feedEndOfStream(codec: MediaCodec) {
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun drainOutput(codec: MediaCodec, endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Codec-specific data for the OGG muxer is read from
                    // codec.outputFormat at finalize time via getCodecConfig(),
                    // so nothing to do here beyond noting the format is ready.
                }
                outputIndex >= 0 -> {
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            onEncodedFrame(outputBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> return
            }
        }
    }

    private fun onEncodedFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val payload = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(payload)
        walWriter?.append(info.presentationTimeUs, payload)
    }

    /**
     * Stops capture and blocks until the capture thread has drained the
     * encoder and closed the WAL file. Does NOT produce the final .ogg --
     * that happens in [finalizeToOgg], called separately by SessionStore so
     * finalize can also run as a recovery step against an orphaned WAL.
     */
    fun stop() {
        if (!recording) return
        recording = false
        captureThread?.join(STOP_JOIN_TIMEOUT_MS)
        captureThread = null

        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null

        runCatching { encoder?.stop() }
        encoder?.release()
        encoder = null

        walWriter?.close()
        walWriter = null
    }

    /**
     * Remuxes a WAL file into a playable Ogg Opus file. Safe to call either
     * right after [stop] (normal path) or, on next app start, against a WAL
     * left behind by a process death (recovery path) -- see
     * SessionStore.recoverUnfinalizedSessions.
     */
    fun finalizeToOgg(walFile: File, outputOggFile: File) {
        OpusFrameWal.Reader(walFile).use { reader ->
            val header = reader.header
            val frames = reader.readAll()

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, header.sampleRateHz, header.channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            }

            val muxer = MediaMuxer(outputOggFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val trackIndex = muxer.addTrack(format)
            muxer.start()
            try {
                for (frame in frames) {
                    val buffer = ByteBuffer.wrap(frame.payload)
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, frame.payload.size, frame.timestampUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    }
                    muxer.writeSampleData(trackIndex, buffer, info)
                }
            } finally {
                muxer.stop()
                muxer.release()
            }
        }
    }
}
