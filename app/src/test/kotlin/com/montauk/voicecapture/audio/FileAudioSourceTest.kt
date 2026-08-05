package com.montauk.voicecapture.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FileAudioSourceTest {

    /** 16kHz mono 16-bit PCM WAV containing [durationMs] of a constant tone. */
    private fun wavBytes(durationMs: Int, amplitude: Int = 1000): ByteArray {
        val sampleCount = SAMPLE_RATE_HZ * durationMs / 1000
        val pcm = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            pcm[i * 2] = (amplitude and 0xFF).toByte()
            pcm[i * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
        }
        return buildWav(SAMPLE_RATE_HZ, channelCount = 1, bitsPerSample = 16, pcm = pcm)
    }

    private fun buildWav(sampleRateHz: Int, channelCount: Int, bitsPerSample: Int, pcm: ByteArray): ByteArray {
        val byteRate = sampleRateHz * channelCount * (bitsPerSample / 8)
        val blockAlign = channelCount * (bitsPerSample / 8)
        val out = ByteArrayOutputStream()
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeLe32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun writeLe16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }
        writeAscii("RIFF"); writeLe32(36 + pcm.size); writeAscii("WAVE")
        writeAscii("fmt "); writeLe32(16); writeLe16(1); writeLe16(channelCount)
        writeLe32(sampleRateHz); writeLe32(byteRate); writeLe16(blockAlign); writeLe16(bitsPerSample)
        writeAscii("data"); writeLe32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private fun sourceFor(wav: ByteArray, label: String = "FILE") = FileAudioSource(openStream = { ByteArrayInputStream(wav) }, deviceLabel = label)

    @Test
    fun `reports sample rate and channel count from the decoded WAV header`() {
        val source = sourceFor(wavBytes(durationMs = 500))
        source.start()
        assertEquals(SAMPLE_RATE_HZ, source.sampleRateHz)
        assertEquals(1, source.channelCount)
    }

    @Test
    fun `deviceLabel plumbs through as-configured, defaulting to FILE`() {
        assertEquals("FILE", sourceFor(wavBytes(100)).deviceLabel)
        assertEquals("MY-CUSTOM-LABEL", sourceFor(wavBytes(100), label = "MY-CUSTOM-LABEL").deviceLabel)
    }

    @Test
    fun `MicAudioSource reports a plain MIC label without touching AudioRecord`() {
        // Deliberately never calls start() -- that's the whole point: this
        // label must be readable in a plain JVM unit test with no real
        // microphone/AudioRecord available.
        assertEquals("MIC", MicAudioSource().deviceLabel)
    }

    @Test
    fun `read paces delivery to roughly real time rather than draining the file instantly`() {
        val source = sourceFor(wavBytes(durationMs = 1_000))
        source.start()
        val chunkBytes = SAMPLE_RATE_HZ * 2 * 100 / 1000 // 100ms of PCM16 mono

        val startNanos = System.nanoTime()
        val buffer = ByteArray(chunkBytes)
        repeat(2) {
            val read = source.read(buffer, 0, buffer.size)
            assertEquals(chunkBytes, read)
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        // 2 chunks of 100ms each should take roughly 200ms of wall-clock time --
        // generous bounds since this is a real Thread.sleep-based test.
        assertTrue("expected >=150ms elapsed pacing 200ms of audio, got ${elapsedMs}ms", elapsedMs >= 150)
        assertTrue("expected <1000ms elapsed pacing 200ms of audio, got ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun `end of file behaves like silence rather than ending the session`() {
        // Only 20ms of real tone in the file.
        val source = sourceFor(wavBytes(durationMs = 20, amplitude = 5000))
        source.start()

        // Ask for 40ms in one read -- more than the file has.
        val chunkBytes = SAMPLE_RATE_HZ * 2 * 40 / 1000
        val buffer = ByteArray(chunkBytes) { -1 } // pre-fill with non-zero so silence padding is verifiable
        val read = source.read(buffer, 0, chunkBytes)

        assertEquals("a short read must never happen -- EOF pads with silence instead", chunkBytes, read)
        val tailStart = SAMPLE_RATE_HZ * 2 * 20 / 1000 // first 20ms is real tone
        for (i in tailStart until chunkBytes) {
            assertEquals("expected silence (zero) byte at index $i past end-of-file", 0, buffer[i].toInt())
        }

        // Recording keeps going -- a second read past EOF must also succeed
        // (all silence), never throw or signal end-of-stream.
        val second = source.read(buffer, 0, chunkBytes)
        assertEquals(chunkBytes, second)
        for (b in buffer) assertEquals(0, b.toInt())
    }

    @Test
    fun `rejects a WAV that is not 16-bit PCM`() {
        val pcm8bit = ByteArray(100)
        val wav = buildWav(SAMPLE_RATE_HZ, channelCount = 1, bitsPerSample = 8, pcm = pcm8bit)
        val source = sourceFor(wav)
        try {
            source.start()
            fail("expected an exception for a non-16-bit WAV")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `rejects a WAV at the wrong sample rate`() {
        val pcm = ByteArray(200)
        val wav = buildWav(sampleRateHz = 44_100, channelCount = 1, bitsPerSample = 16, pcm = pcm)
        val source = sourceFor(wav)
        try {
            source.start()
            fail("expected an exception for a non-16kHz WAV")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
    }
}
