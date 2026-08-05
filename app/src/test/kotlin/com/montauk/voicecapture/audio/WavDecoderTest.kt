package com.montauk.voicecapture.audio

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WavDecoderTest {

    /** Builds a minimal, well-formed 16-bit PCM WAV (no filler chunks) for the synthetic-input tests below. */
    private fun buildWav(sampleRateHz: Int, channelCount: Int, bitsPerSample: Int, pcm: ByteArray): ByteArray {
        val byteRate = sampleRateHz * channelCount * (bitsPerSample / 8)
        val blockAlign = channelCount * (bitsPerSample / 8)
        val out = java.io.ByteArrayOutputStream()
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeLe32(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        fun writeLe16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
        writeAscii("RIFF")
        writeLe32(36 + pcm.size)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeLe32(16)
        writeLe16(1) // PCM
        writeLe16(channelCount)
        writeLe32(sampleRateHz)
        writeLe32(byteRate)
        writeLe16(blockAlign)
        writeLe16(bitsPerSample)
        writeAscii("data")
        writeLe32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun openFixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }

    @Test
    fun `decodes the kitchen-remodel fixture as 16kHz mono 16-bit`() {
        val result = WavDecoder.decode(openFixture("kitchen-remodel.wav"))
        assertEquals(16_000, result.sampleRateHz)
        assertEquals(1, result.channelCount)
        assertEquals(16, result.bitsPerSample)
        assertTrue("expected non-empty PCM data", result.pcm.isNotEmpty())
    }

    @Test
    fun `decodes the marathon-training fixture as 16kHz mono 16-bit`() {
        val result = WavDecoder.decode(openFixture("marathon-training.wav"))
        assertEquals(16_000, result.sampleRateHz)
        assertEquals(1, result.channelCount)
        assertEquals(16, result.bitsPerSample)
        assertTrue("expected non-empty PCM data", result.pcm.isNotEmpty())
    }

    @Test
    fun `round-trips a synthetic WAV built with a filler chunk before data`() {
        val pcm = ByteArray(400) { it.toByte() }
        val withoutFiller = buildWav(16_000, 1, 16, pcm)
        // Splice a "FLLR" filler chunk in between "fmt " and "data", mirroring
        // what afconvert emits in the real fixtures -- proves the chunk walker
        // skips unknown chunks by their declared size rather than assuming a
        // fixed header layout.
        val fmtChunkEnd = 12 + 8 + 16 // "WAVE" header + "fmt " id/size + 16-byte fmt body
        val filler = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte(), 4, 0, 0, 0, 0, 0, 0, 0)
        val spliced = withoutFiller.copyOfRange(0, fmtChunkEnd) + filler + withoutFiller.copyOfRange(fmtChunkEnd, withoutFiller.size)

        val result = WavDecoder.decode(ByteArrayInputStream(spliced))
        assertEquals(16_000, result.sampleRateHz)
        assertEquals(1, result.channelCount)
        assertEquals(16, result.bitsPerSample)
        org.junit.Assert.assertArrayEquals(pcm, result.pcm)
    }

    @Test
    fun `rejects a non-RIFF stream`() {
        try {
            WavDecoder.decode(ByteArrayInputStream("not a wav file at all!!".toByteArray()))
            fail("expected an exception for a non-RIFF stream")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
