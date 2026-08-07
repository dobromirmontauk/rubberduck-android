package com.montauk.voicecapture.logging

import java.io.File

/** What [RubberduckLog] writes formatted lines to besides logcat -- [RollingFileSink] in production, a fake in tests. */
interface LogSink {
    fun append(line: String)
}

/**
 * Rolling on-device log file (bead asn-jht): a bounded active+rolled file
 * pair under the app's files dir so `adb pull`-ing app logs never depends on
 * the process still being alive or on logcat's own ring buffer, which
 * rotates away old lines within minutes on this device (confirmed blackouts
 * twice on 2026-08-06 and again that same night). The active file rolls to
 * `<name>.1` (overwriting whatever rolled file was already there) the
 * instant appending the next line would push it past [maxBytesPerFile], so
 * total on-disk usage never exceeds roughly `2 * maxBytesPerFile` -- there is
 * never a `.2`, `.3`, etc.
 *
 * Not thread-safe by itself -- callers serialize access (see
 * [RubberduckLog], whose single `i()` entry point already does).
 */
class RollingFileSink(
    private val activeFile: File,
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
) : LogSink {

    private val rolledFile: File = File(activeFile.parentFile, "${activeFile.name}$ROLLED_SUFFIX")

    override fun append(line: String) {
        activeFile.parentFile?.mkdirs()
        val incomingBytes = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L // +1 for the trailing newline
        rotateIfNeeded(incomingBytes)
        activeFile.appendText(line + "\n")
    }

    private fun rotateIfNeeded(incomingBytes: Long) {
        if (activeFile.exists() && activeFile.length() + incomingBytes > maxBytesPerFile) {
            rolledFile.delete()
            activeFile.renameTo(rolledFile)
        }
    }

    companion object {
        const val LOG_FILE_NAME = "rubberduck.log"
        const val ROLLED_SUFFIX = ".1"
        const val DEFAULT_MAX_BYTES_PER_FILE = 2L * 1024 * 1024
    }
}
