package com.montauk.voicecapture.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingFileSinkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `append writes each line to the active file`() {
        val activeFile = File(tempFolder.root, "test.log")
        val sink = RollingFileSink(activeFile, maxBytesPerFile = 1024)

        sink.append("line one")
        sink.append("line two")

        assertEquals("line one\nline two\n", activeFile.readText())
    }

    @Test
    fun `rotates the active file to the rolled suffix once the cap would be exceeded`() {
        val activeFile = File(tempFolder.root, "test.log")
        val rolledFile = File(tempFolder.root, "test.log.1")
        val sink = RollingFileSink(activeFile, maxBytesPerFile = 20)

        sink.append("aaaaaaaaaa") // 11 bytes with the trailing newline -- under the 20-byte cap
        assertFalse(rolledFile.exists())

        sink.append("bbbbbbbbbb") // appending this would push the active file past the cap
        assertTrue(rolledFile.exists())
        assertEquals("aaaaaaaaaa\n", rolledFile.readText())
        assertEquals("bbbbbbbbbb\n", activeFile.readText())
    }

    @Test
    fun `never keeps more than an active plus one rolled file`() {
        val activeFile = File(tempFolder.root, "test.log")
        val sink = RollingFileSink(activeFile, maxBytesPerFile = 12)

        repeat(5) { sink.append("x".repeat(10)) }

        val logFiles = tempFolder.root.listFiles { f -> f.name.startsWith("test.log") }
        assertEquals(2, logFiles?.size)
    }
}
