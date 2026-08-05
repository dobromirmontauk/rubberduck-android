package com.montauk.voicecapture.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OpusFrameWalTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("opus-wal-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `round-trips header and frames written by the writer`() {
        val file = File(dir, "audio.wal")
        val framePayloads = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5, 6, 7, 8),
            ByteArray(0), // a zero-length packet should still round-trip cleanly
        )

        OpusFrameWal.Writer(file, OpusFrameWal.Header(sampleRateHz = 16_000, channels = 1)).use { writer ->
            framePayloads.forEachIndexed { index, payload -> writer.append(timestampUs = index * 20_000L, payload = payload) }
        }

        OpusFrameWal.Reader(file).use { reader ->
            assertEquals(16_000, reader.header.sampleRateHz)
            assertEquals(1, reader.header.channels)

            val frames = reader.readAll()
            assertEquals(framePayloads.size, frames.size)
            frames.forEachIndexed { index, frame ->
                assertEquals(index * 20_000L, frame.timestampUs)
                assertArrayEquals(framePayloads[index], frame.payload)
            }
        }
    }

    @Test
    fun `a truncated trailing frame is dropped instead of throwing`() {
        val file = File(dir, "audio.wal")
        OpusFrameWal.Writer(file, OpusFrameWal.Header(sampleRateHz = 16_000, channels = 1)).use { writer ->
            writer.append(timestampUs = 0L, payload = byteArrayOf(9, 9, 9))
            writer.append(timestampUs = 20_000L, payload = byteArrayOf(1, 2, 3, 4))
        }

        // Simulate a process death mid-write-of-the-last-frame: chop off the tail bytes.
        RandomAccessFile(file, "rw").use { raf -> raf.setLength(raf.length() - 2) }

        OpusFrameWal.Reader(file).use { reader ->
            val frames = reader.readAll()
            // Only the first, fully-written frame survives -- the crash-safety invariant.
            assertEquals(1, frames.size)
            assertArrayEquals(byteArrayOf(9, 9, 9), frames.first().payload)
        }
    }

    @Test
    fun `writer appending across two instances preserves earlier frames`() {
        val file = File(dir, "audio.wal")
        OpusFrameWal.Writer(file, OpusFrameWal.Header(sampleRateHz = 16_000, channels = 1)).use { writer ->
            writer.append(timestampUs = 0L, payload = byteArrayOf(1))
        }
        // Re-opening the writer simulates resuming a WAL that was already
        // flushed to disk (e.g. across a restart), and must not clobber it.
        OpusFrameWal.Writer(file, OpusFrameWal.Header(sampleRateHz = 16_000, channels = 1)).use { writer ->
            writer.append(timestampUs = 20_000L, payload = byteArrayOf(2))
        }

        OpusFrameWal.Reader(file).use { reader ->
            val frames = reader.readAll()
            assertEquals(2, frames.size)
            assertArrayEquals(byteArrayOf(1), frames[0].payload)
            assertArrayEquals(byteArrayOf(2), frames[1].payload)
        }
    }
}
