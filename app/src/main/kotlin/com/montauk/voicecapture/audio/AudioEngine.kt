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
        private const val PROBE_MAX_ATTEMPTS = 50
    }

    /** Hook for later Bluetooth-mic work; null means "use the system default input". */
    var preferredInputDeviceId: Int? = null

    /**
     * Tee for raw PCM frames, fired on the capture thread right after each
     * successful [AudioRecord.read] -- alongside, not instead of, the
     * Opus-encode-then-WAL path below. Set by [com.montauk.voicecapture.service.RecordingService]
     * to feed [com.montauk.voicecapture.stt.StreamingSttClient.sendPcm]. Must
     * return quickly and never throw: this call sits directly in the capture
     * loop, ahead of the WAL write, so a slow or failing STT tee must never
     * delay or drop audio frames -- backpressure handling belongs entirely to
     * the STT client's own `sendPcm` implementation, not to this hook.
     */
    var onPcmFrame: ((pcm: ByteArray, length: Int) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var walWriter: OpusFrameWal.Writer? = null
    private var captureThread: Thread? = null
    @Volatile private var recording = false

    // Encoder presentation timestamps are relative to this, not to the
    // device's boot clock -- otherwise a device with a long uptime bakes a
    // large, meaningless start offset into the muxed audio.ogg's container
    // metadata (harmless for decoding, but confusing duration/seek behavior
    // in some players and downstream tooling that trusts container timing).
    @Volatile private var recordingStartNanos: Long = 0L

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
        recordingStartNanos = System.nanoTime()

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
                    runCatching { onPcmFrame?.invoke(pcmBuffer, bytesRead) }
                        .onFailure { e -> Log.w(TAG, "onPcmFrame tee failed (STT unaffected audio path)", e) }
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
        codec.queueInputBuffer(inputIndex, 0, length, elapsedPresentationTimeUs(), 0)
    }

    private fun feedEndOfStream(codec: MediaCodec) {
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, elapsedPresentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun elapsedPresentationTimeUs(): Long = (System.nanoTime() - recordingStartNanos) / 1_000

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

            // MediaMuxer's OGG writer needs the Opus codec-config (OpusHead, in
            // `csd-0`, plus the `csd-1`/`csd-2` pre-skip/seek-preroll buffers the
            // encoder emits alongside it) on the track's MediaFormat *before*
            // any sample data is written -- otherwise it fails with "Did not
            // get valid opus header before first sample data". The live
            // capture loop in [runCaptureLoop]/[drainOutput] deliberately never
            // persists that config buffer into the WAL (it's not audio data,
            // and the WAL format is audio-frames-only per docs/audio-wal.md),
            // so it isn't recoverable from the WAL alone -- re-derive it here
            // by running a throwaway encoder with the same (sampleRate,
            // channels, bitRate) just long enough to observe its
            // INFO_OUTPUT_FORMAT_CHANGED event. Works identically for the
            // normal-stop and crash-recovery paths since both only need the
            // WAL header's sampleRate/channels plus this class's own default
            // bitRate (both callers construct `AudioEngine()` with no override).
            val format = probeOpusCodecConfig(header.sampleRateHz, header.channels)

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

    /**
     * Runs a short-lived Opus encoder configured identically to [start]'s,
     * feeding it a little silence, purely to capture the `csd-0`/`csd-1`/
     * `csd-2` buffers from its [MediaCodec.INFO_OUTPUT_FORMAT_CHANGED] event.
     * Falls back to the plain (config-less) format if the encoder never
     * reports one within the attempt budget -- callers still get a file,
     * just possibly one the OGG muxer rejects, which is no worse than before
     * this method existed.
     */
    private fun probeOpusCodecConfig(sampleRateHz: Int, channelCount: Int): MediaFormat {
        val inputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(inputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            val silence = ByteArray((sampleRateHz / 50) * channelCount * 2) // ~20ms of 16-bit silence
            val bufferInfo = MediaCodec.BufferInfo()
            repeat(PROBE_MAX_ATTEMPTS) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.getInputBuffer(inputIndex)?.apply {
                        clear()
                        put(silence)
                    }
                    codec.queueInputBuffer(inputIndex, 0, silence.size, 0, 0)
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return codec.outputFormat
                    outputIndex >= 0 -> codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            Log.w(TAG, "never observed INFO_OUTPUT_FORMAT_CHANGED while probing Opus codec config; muxer may reject the output")
            return inputFormat
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }
}
