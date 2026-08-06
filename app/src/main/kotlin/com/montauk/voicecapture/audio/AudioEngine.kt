package com.montauk.voicecapture.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * The recording core: [AudioSource] -> MediaCodec Opus encoder -> [OpusFrameWal].
 *
 * Capture runs on its own dedicated thread so it is never blocked by UI, STT
 * streaming, or upload work. The WAL write in [OpusFrameWal.Writer.append] is
 * the crash-safety boundary described in docs/audio-wal.md: every call to
 * [onEncodedFrame] durably persists before the next chunk of PCM is even read.
 *
 * Source-agnostic (bead vn-edu.20): [start] takes an [AudioSource] rather
 * than owning an [android.media.AudioRecord] directly, so a debug-only
 * [FileAudioSource] can stand in for [MicAudioSource] without this class (or
 * anything downstream of it) knowing the difference. This engine is
 * configured for a fixed [sampleRateHz]/[channelCount] -- [start] fails fast
 * if the given source doesn't actually report that rate, rather than
 * silently mis-encoding.
 */
class AudioEngine(
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val channelCount: Int = 1,
    private val bitRate: Int = DEFAULT_BIT_RATE,
    /** Bead asn-r60: how much trailing quiet audio auto-pause keeps ready to prepend on resume -- within the bead's 3-5s spec range. */
    private val ringBufferMs: Long = DEFAULT_RING_BUFFER_MS,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_BIT_RATE = 32_000
        const val DEFAULT_RING_BUFFER_MS = 4_000L
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
        private const val TAG = "AudioEngine"
        private const val TIMEOUT_US = 10_000L
        private const val STOP_JOIN_TIMEOUT_MS = 5_000L
        private const val PROBE_MAX_ATTEMPTS = 50
    }

    /**
     * Tee for raw PCM frames, fired on the capture thread right after each
     * successful [AudioSource.read] -- alongside, not instead of, the
     * Opus-encode-then-WAL path below. Set by [com.montauk.voicecapture.service.RecordingService]
     * to feed [com.montauk.voicecapture.stt.StreamingSttClient.sendPcm]. Must
     * return quickly and never throw: this call sits directly in the capture
     * loop, ahead of the WAL write, so a slow or failing STT tee must never
     * delay or drop audio frames -- backpressure handling belongs entirely to
     * the STT client's own `sendPcm` implementation, not to this hook.
     */
    var onPcmFrame: ((pcm: ByteArray, length: Int) -> Unit)? = null

    /**
     * Bead asn-r60: invoked (on the capture thread, synchronously) with the
     * exact PCM chunks a soft-pause resume is about to feed into the
     * encoder/WAL, oldest-first -- lets [com.montauk.voicecapture.service.RecordingService]
     * mirror-feed the same audio to its STT client so the live transcript
     * doesn't lose the ring-buffered onset of speech. Never invoked for a
     * hard-pause resume (that mode never buffers -- see [CapturePauseGate]).
     */
    var onRingBufferFlush: ((List<ByteArray>) -> Unit)? = null

    private var audioSource: AudioSource? = null
    private var encoder: MediaCodec? = null
    private var walWriter: OpusFrameWal.Writer? = null
    private var captureThread: Thread? = null
    @Volatile private var recording = false

    /**
     * Bead asn-r60: gates persistence per [AudioPauseMode] -- see its own
     * KDoc for why the actual pause/buffer/flush decisions live in this pure
     * class rather than inline here. `@Volatile`-safe to flip from another
     * thread (RecordingService's manual-pause path) the same way [recording]
     * already is; the one transition that returns audio to flush (soft
     * pause -> active) only ever happens from a call made *from* the capture
     * thread itself -- see [setPauseMode]'s KDoc.
     */
    @Volatile private var pauseGate = CapturePauseGate(ringBufferCapacityBytes = ringBufferCapacityBytes())

    private fun ringBufferCapacityBytes(): Int =
        (sampleRateHz.toLong() * channelCount * BYTES_PER_SAMPLE * ringBufferMs / 1_000L).toInt().coerceAtLeast(1)

    /**
     * Cumulative PCM bytes actually fed to the encoder so far (persisted
     * frames only -- never counts anything dropped or ring-buffered while
     * paused). Bead asn-r60: this, not wall-clock time, is what
     * [presentationTimeUsFor] derives the encoder's presentation timestamps
     * from, so a pause -- during which nothing is fed -- produces zero gap in
     * `audio.ogg`'s own internal timeline. The file is simply shorter, not
     * "silent for N seconds then continuing" or, worse, timestamp-discontinuous
     * in a way the OGG muxer might mishandle.
     */
    private var cumulativeFedBytes: Long = 0L

    private fun presentationTimeUsFor(bytesAboutToFeed: Int): Long {
        val samplesFrames = cumulativeFedBytes / (channelCount.toLong() * BYTES_PER_SAMPLE)
        val presentationTimeUs = samplesFrames * 1_000_000L / sampleRateHz
        cumulativeFedBytes += bytesAboutToFeed
        return presentationTimeUs
    }

    /**
     * Starts capturing [audioSource] into [walFile]. Returns once the
     * encoder and source are both running; encoding happens on a background
     * thread until [stop] is called. Throws if [audioSource] doesn't report
     * this engine's configured [sampleRateHz]/[channelCount] -- see class KDoc.
     */
    fun start(walFile: File, audioSource: AudioSource) {
        check(!recording) { "AudioEngine already recording" }

        audioSource.start()
        require(audioSource.sampleRateHz == sampleRateHz && audioSource.channelCount == channelCount) {
            "AudioSource '${audioSource.deviceLabel}' reports ${audioSource.sampleRateHz}Hz/" +
                "${audioSource.channelCount}ch but AudioEngine is configured for ${sampleRateHz}Hz/${channelCount}ch"
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        walWriter = OpusFrameWal.Writer(walFile, OpusFrameWal.Header(sampleRateHz, channelCount))
        this.audioSource = audioSource
        encoder = codec
        cumulativeFedBytes = 0L
        pauseGate = CapturePauseGate(ringBufferCapacityBytes = ringBufferCapacityBytes())

        codec.start()
        recording = true

        captureThread = thread(name = "audio-engine-capture") {
            runCaptureLoop(audioSource, codec)
        }
    }

    /**
     * Bead asn-r60: switches the current [AudioPauseMode] -- see
     * [CapturePauseGate] for the persist/buffer/drop semantics per mode.
     *
     * Safe to call from any thread for a transition into [AudioPauseMode.HARD]
     * or out of it into [AudioPauseMode.ACTIVE] (those never produce
     * anything to flush -- see [CapturePauseGate.setMode]'s KDoc), which is
     * exactly the manual-pause-button path [com.montauk.voicecapture.service.RecordingService]
     * drives from outside the capture thread, the same way [stop] already
     * flips [recording] cross-thread. The one transition that *does* flush
     * (soft pause -> active) touches [encoder] directly and must only ever
     * be triggered from the capture thread itself -- see the VAD-driven
     * auto-resume path in [com.montauk.voicecapture.service.RecordingService],
     * which decides to resume from inside [onPcmFrame] (already running on
     * that thread), never from a coroutine or the UI thread.
     */
    fun setPauseMode(mode: AudioPauseMode) {
        val decision = pauseGate.setMode(mode)
        if (decision.toPersist.isEmpty()) return
        onRingBufferFlush?.invoke(decision.toPersist)
        val codec = encoder ?: return
        decision.toPersist.forEach { chunk -> feedInput(codec, chunk, chunk.size) }
    }

    private fun runCaptureLoop(source: AudioSource, codec: MediaCodec) {
        val pcmBuffer = ByteArray(source.recommendedReadBufferSize)
        try {
            while (recording) {
                val bytesRead = source.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead > 0) {
                    // Always fires regardless of pause mode -- VAD and the
                    // mic-level meter need to see live audio even while
                    // quiet/paused (bead asn-r60: that's what lets the
                    // service detect an auto-resume, and keeps SPEAKING/QUIET
                    // genuinely VAD-driven rather than frozen the moment a
                    // pause starts).
                    runCatching { onPcmFrame?.invoke(pcmBuffer, bytesRead) }
                        .onFailure { e -> Log.w(TAG, "onPcmFrame tee failed (STT unaffected audio path)", e) }
                    pauseGate.onFrame(pcmBuffer, bytesRead).toPersist.forEach { chunk ->
                        feedInput(codec, chunk, chunk.size)
                    }
                }
                drainOutput(codec, endOfStream = false)
            }
            // Recording stopped: flush remaining PCM through the encoder and drain it fully.
            // Nothing still sitting in the ring buffer (a session stopped
            // mid-auto-pause) is ever flushed here -- drop-at-source means a
            // quiet span that never resumed to speech stays dropped, exactly
            // like a hard pause that's never resumed.
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
        codec.queueInputBuffer(inputIndex, 0, length, presentationTimeUsFor(length), 0)
    }

    private fun feedEndOfStream(codec: MediaCodec) {
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUsFor(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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

        runCatching { audioSource?.stop() }
        audioSource = null

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
