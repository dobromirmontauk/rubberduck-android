package com.montauk.voicecapture.audio

import java.io.InputStream

/**
 * Debug-only [AudioSource] (bead vn-edu.20) that decodes a 16kHz mono PCM16
 * WAV file up front and delivers it in real-time-paced chunks, matching
 * [AudioEngine]'s live-capture cadence closely enough that everything
 * downstream (WAL, STT tee, mic-level meter, silence detector) behaves the
 * same as it would against a real [MicAudioSource]. Exists because the
 * macOS emulator's host-mic passthrough is unreliable (a few seconds of
 * audio, then silence) -- this is the primary way to exercise live
 * transcription end-to-end from Android Studio without a working mic.
 *
 * Never loops: once the decoded PCM is exhausted, [read] keeps returning
 * silence (zero-filled chunks) rather than ending the session, so recording
 * continues normally until the user taps Stop -- looping would make a short
 * fixture file produce a garbled, discontinuous transcript instead.
 *
 * [openStream] is a factory rather than an already-open [InputStream] so the
 * caller controls exactly when the (blocking) file/asset read happens --
 * inside [start], not at construction time.
 */
class FileAudioSource(
    private val openStream: () -> InputStream,
    override val deviceLabel: String = "FILE",
) : AudioSource {

    override var sampleRateHz: Int = 0
        private set
    override var channelCount: Int = 0
        private set
    override var recommendedReadBufferSize: Int = 0
        private set

    private var pcm: ByteArray = EMPTY_PCM
    private var position: Int = 0
    private var bytesDelivered: Long = 0L
    private var bytesPerSecond: Long = 1L
    private var startNanos: Long = 0L

    override fun start() {
        val decoded = openStream().use { WavDecoder.decode(it) }
        require(decoded.bitsPerSample == 16) {
            "FileAudioSource only supports 16-bit PCM WAV, got ${decoded.bitsPerSample}-bit"
        }
        require(decoded.sampleRateHz == AudioEngine.DEFAULT_SAMPLE_RATE_HZ && decoded.channelCount == 1) {
            "FileAudioSource requires a ${AudioEngine.DEFAULT_SAMPLE_RATE_HZ}Hz mono WAV, got " +
                "${decoded.sampleRateHz}Hz/${decoded.channelCount}ch"
        }
        sampleRateHz = decoded.sampleRateHz
        channelCount = decoded.channelCount
        pcm = decoded.pcm
        position = 0
        bytesDelivered = 0L
        bytesPerSecond = sampleRateHz.toLong() * channelCount * BYTES_PER_SAMPLE
        // A fixed ~100ms chunk size -- deliberately not derived from the real
        // android.media.AudioRecord.getMinBufferSize (unlike MicAudioSource):
        // that keeps this class free of any Android framework dependency, so
        // decode/pacing/EOF logic is plain-JVM-testable (see FileAudioSourceTest),
        // and 100ms is a close enough approximation of AudioRecord's own
        // cadence for the WAL/STT/meter pipeline downstream not to notice.
        recommendedReadBufferSize = (bytesPerSecond * READ_CHUNK_MS / 1000).toInt()
        startNanos = System.nanoTime()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        paceUntilDue(length)

        val remaining = (pcm.size - position).coerceAtLeast(0)
        val fromPcm = length.coerceAtMost(remaining)
        if (fromPcm > 0) {
            System.arraycopy(pcm, position, buffer, offset, fromPcm)
            position += fromPcm
        }
        if (fromPcm < length) {
            // End of file: pad the rest of this chunk with silence rather
            // than returning a short read -- and every read after this one
            // keeps doing the same, since fromPcm stays 0 once position
            // reaches pcm.size.
            java.util.Arrays.fill(buffer, offset + fromPcm, offset + length, 0)
        }
        bytesDelivered += length
        return length
    }

    /**
     * Blocks until the wall-clock time elapsed since [start] justifies
     * having delivered [bytesDelivered] + [upcomingChunkLength] bytes --
     * without this, a tight JVM read loop would drain the whole file in
     * milliseconds, blowing past the real-time pacing the WAL/STT pipeline
     * expects from a live microphone.
     */
    private fun paceUntilDue(upcomingChunkLength: Int) {
        val targetElapsedNanos = (bytesDelivered + upcomingChunkLength) * NANOS_PER_SECOND / bytesPerSecond
        val actualElapsedNanos = System.nanoTime() - startNanos
        val sleepNanos = targetElapsedNanos - actualElapsedNanos
        if (sleepNanos > 0) {
            Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
        }
    }

    override fun stop() {
        // Nothing to release: the decoded PCM is a plain in-memory ByteArray,
        // and openStream()'s InputStream was already closed at the end of start().
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2L // PCM16
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val READ_CHUNK_MS = 100L
        private val EMPTY_PCM = ByteArray(0)
    }
}
