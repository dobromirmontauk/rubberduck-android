package com.montauk.voicecapture.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    @Test
    fun `starts empty`() {
        val buffer = PcmRingBuffer(capacityBytes = 10)
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.sizeBytes())
    }

    @Test
    fun `write then drainOrdered returns chunks oldest-first and clears the buffer`() {
        val buffer = PcmRingBuffer(capacityBytes = 100)
        buffer.write(bytes(1, 2))
        buffer.write(bytes(3, 4))

        val drained = buffer.drainOrdered()

        assertEquals(2, drained.size)
        assertArrayEquals(bytes(1, 2), drained[0])
        assertArrayEquals(bytes(3, 4), drained[1])
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `drops oldest whole chunks once capacity would be exceeded`() {
        // capacity 5 bytes: three 2-byte chunks (6 bytes total) must drop the first.
        val buffer = PcmRingBuffer(capacityBytes = 5)
        buffer.write(bytes(1, 1))
        buffer.write(bytes(2, 2))
        buffer.write(bytes(3, 3))

        val drained = buffer.drainOrdered()

        assertEquals(2, drained.size)
        assertArrayEquals(bytes(2, 2), drained[0])
        assertArrayEquals(bytes(3, 3), drained[1])
    }

    @Test
    fun `never exceeds capacity across many writes`() {
        val buffer = PcmRingBuffer(capacityBytes = 10)
        repeat(50) { buffer.write(bytes(1, 2, 3)) }

        assertTrue(buffer.sizeBytes() <= 10)
    }

    @Test
    fun `a single chunk larger than capacity is kept whole rather than dropped`() {
        val buffer = PcmRingBuffer(capacityBytes = 3)
        buffer.write(bytes(1, 2, 3, 4, 5))

        val drained = buffer.drainOrdered()

        assertEquals(1, drained.size)
        assertArrayEquals(bytes(1, 2, 3, 4, 5), drained[0])
    }

    @Test
    fun `clear empties the buffer without draining`() {
        val buffer = PcmRingBuffer(capacityBytes = 100)
        buffer.write(bytes(1, 2))

        buffer.clear()

        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.sizeBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive capacity`() {
        PcmRingBuffer(capacityBytes = 0)
    }
}
