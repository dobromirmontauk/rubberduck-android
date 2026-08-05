package com.montauk.voicecapture.stt.integration

import java.io.DataInputStream
import java.io.InputStream

/**
 * Minimal RIFF/WAVE chunk walker -- just enough to pull 16-bit PCM samples
 * out of the fixtures under `app/src/integrationTest/resources/` for
 * [AssemblyAiLiveStreamingTest]. Not a general-purpose WAV parser: it walks
 * chunks by their declared size rather than assuming a fixed header layout,
 * because `afconvert` (used to generate the fixtures) emits an extra "FLLR"
 * filler chunk between "fmt " and "data" for alignment.
 */
internal object WavPcmReader {
    data class WavAudio(val sampleRateHz: Int, val channelCount: Int, val bitsPerSample: Int, val pcm: ByteArray)

    fun read(input: InputStream): WavAudio {
        val stream = DataInputStream(input)
        require(readAscii(stream, 4) == "RIFF") { "not a RIFF file" }
        readLe32(stream) // overall RIFF size, unused
        require(readAscii(stream, 4) == "WAVE") { "not a WAVE file" }

        var sampleRateHz = 0
        var channelCount = 0
        var bitsPerSample = 0
        var pcm: ByteArray? = null

        while (pcm == null) {
            val chunkId = readAscii(stream, 4)
            val chunkSize = readLe32(stream)
            when (chunkId) {
                "fmt " -> {
                    val fmt = ByteArray(chunkSize)
                    stream.readFully(fmt)
                    channelCount = leShort(fmt, 2)
                    sampleRateHz = leInt(fmt, 4)
                    bitsPerSample = leShort(fmt, 14)
                }
                "data" -> {
                    pcm = ByteArray(chunkSize)
                    stream.readFully(pcm)
                }
                else -> stream.skipFully(chunkSize)
            }
            if (chunkSize % 2 == 1) stream.skipFully(1) // chunks are word-aligned
        }
        return WavAudio(sampleRateHz, channelCount, bitsPerSample, pcm)
    }

    private fun readAscii(stream: DataInputStream, len: Int): String {
        val bytes = ByteArray(len)
        stream.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readLe32(stream: DataInputStream): Int {
        val bytes = ByteArray(4)
        stream.readFully(bytes)
        return leInt(bytes, 0)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun DataInputStream.skipFully(n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                readByte() // guaranteed forward progress even if skipBytes stalls
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
