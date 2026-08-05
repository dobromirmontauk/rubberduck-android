package com.montauk.voicecapture.upload

import java.io.File
import java.security.MessageDigest

/**
 * Git LFS pointer-file math: the sha256 "oid" Git LFS identifies an object
 * by, and the exact pointer-file text that gets committed in place of the
 * real bytes (see https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md).
 *
 * Pulled out of [GitHubBundleUploader] with no OkHttp/Android dependency so
 * the pointer format and hashing are coverable by plain JVM unit tests.
 */
object GitHubLfsPointer {
    private const val POINTER_VERSION_LINE = "version https://git-lfs.github.com/spec/v1"

    data class Oid(val sha256Hex: String, val sizeBytes: Long)

    /** Streams [file] through SHA-256 without loading it fully into memory. */
    fun computeOid(file: File): Oid {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return Oid(sha256Hex = digest.digest().toHex(), sizeBytes = file.length())
    }

    /** The exact bytes that get committed to git in place of the real file content. */
    fun pointerText(oid: Oid): String =
        "$POINTER_VERSION_LINE\noid sha256:${oid.sha256Hex}\nsize ${oid.sizeBytes}\n"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
