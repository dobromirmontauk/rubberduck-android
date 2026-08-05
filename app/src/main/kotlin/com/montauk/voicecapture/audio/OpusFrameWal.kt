package com.montauk.voicecapture.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Append-only, crash-safe write-ahead log of encoded Opus packets.
 *
 * See docs/audio-wal.md for the full rationale. Deliberately has zero Android
 * framework dependency so its framing logic can be covered by plain JVM tests.
 */
object OpusFrameWal {

    private val MAGIC_BYTES = byteArrayOf('V'.code.toByte(), 'C'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte())
    const val HEADER_SIZE_BYTES = 16
    private const val FRAME_PREFIX_SIZE_BYTES = 4 + 8 // length + timestampUs

    data class Header(val sampleRateHz: Int, val channels: Int)

    data class Frame(val timestampUs: Long, val payload: ByteArray)

    /**
     * Appends frames to [file]. Every [append] call flushes and fsyncs before
     * returning, so a process death can lose at most the frame currently being
     * written -- never a previously appended one.
     */
    class Writer(file: File, header: Header) : AutoCloseable {
        private val raf = RandomAccessFile(file, "rw")

        init {
            if (raf.length() == 0L) {
                writeHeader(header)
            } else {
                // Re-opening an existing WAL (shouldn't normally happen mid-session,
                // but keep append-safe rather than clobbering prior audio).
                raf.seek(raf.length())
            }
        }

        private fun writeHeader(header: Header) {
            val buf = ByteBuffer.allocate(HEADER_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(MAGIC_BYTES)
            buf.putInt(header.sampleRateHz)
            buf.put(header.channels.toByte())
            buf.put(ByteArray(7)) // reserved
            raf.seek(0)
            raf.write(buf.array())
        }

        fun append(timestampUs: Long, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
            val buf = ByteBuffer.allocate(FRAME_PREFIX_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(length)
            buf.putLong(timestampUs)
            raf.write(buf.array())
            raf.write(payload, offset, length)
            raf.fd.sync()
        }

        override fun close() {
            raf.close()
        }
    }

    /**
     * Reads back frames written by [Writer]. Stops cleanly at a truncated
     * trailing frame (the crash-recovery case) instead of throwing.
     */
    class Reader(file: File) : AutoCloseable {
        private val input = FileInputStream(file)
        val header: Header

        init {
            val headerBytes = input.readNBytesCompat(HEADER_SIZE_BYTES)
            require(headerBytes.size == HEADER_SIZE_BYTES) { "WAL file too short for header: ${file.path}" }
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            require(magic.contentEquals(MAGIC_BYTES)) { "Not a voice-capture WAL file (bad magic): ${file.path}" }
            val sampleRate = buf.int
            val channels = buf.get().toInt()
            header = Header(sampleRate, channels)
        }

        /** Reads every complete frame. Returns fewer frames than were written only if the file was truncated mid-frame. */
        fun readAll(): List<Frame> {
            val frames = mutableListOf<Frame>()
            while (true) {
                frames.add(readNextFrame() ?: break)
            }
            return frames
        }

        private fun readNextFrame(): Frame? {
            val prefix = input.readNBytesCompat(FRAME_PREFIX_SIZE_BYTES)
            if (prefix.isEmpty()) return null // clean EOF between frames
            if (prefix.size < FRAME_PREFIX_SIZE_BYTES) return null // truncated prefix, discard tail

            val buf = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN)
            val length = buf.int
            val timestampUs = buf.long
            if (length < 0) return null

            val payload = input.readNBytesCompat(length)
            if (payload.size < length) return null // truncated payload, discard tail

            return Frame(timestampUs, payload)
        }

        override fun close() {
            input.close()
        }
    }
}

/** [java.io.InputStream.readNBytes] equivalent that works down to our minSdk (24 requires API 33 for readNBytes(int)). */
private fun FileInputStream.readNBytesCompat(count: Int): ByteArray {
    if (count == 0) return ByteArray(0)
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = this.read(out, read, count - read)
        if (n < 0) return out.copyOf(read) // hit EOF before filling the request; caller treats short reads as "no more complete data"
        read += n
    }
    return out
}
