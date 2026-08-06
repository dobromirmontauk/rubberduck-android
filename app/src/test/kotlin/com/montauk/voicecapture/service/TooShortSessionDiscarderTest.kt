package com.montauk.voicecapture.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bead vn-edu.56: "discard removes WAL/partial files (assert on disk)" --
 * [TooShortSessionDiscarder.discard] against a real temp directory standing
 * in for a session dir mid-recording (audio.wal present, live-transcript.jsonl
 * with a few event lines already written, no audio.ogg/meta.json since
 * finalize never runs for a discarded session).
 */
class TooShortSessionDiscarderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fakeSessionDir(): File {
        val dir = tempFolder.newFolder("2026-08-05_1200_ab12")
        File(dir, "audio.wal").writeBytes(ByteArray(1_024) { 0x42 })
        File(dir, "live-transcript.jsonl").writeText("""{"t0Ms":0,"t1Ms":900,"text":"hi","final":true}""" + "\n")
        return dir
    }

    @Test
    fun `discard deletes the entire session directory`() {
        val dir = fakeSessionDir()
        assertTrue(File(dir, "audio.wal").exists())

        val result = TooShortSessionDiscarder.discard(dir)

        assertTrue(result)
        assertFalse("session directory must be gone entirely", dir.exists())
    }

    @Test
    fun `discard on an already-missing directory is a harmless no-op`() {
        val dir = File(tempFolder.root, "never-existed_ab12")
        assertFalse(dir.exists())

        val result = TooShortSessionDiscarder.discard(dir)

        assertTrue(result)
    }
}
