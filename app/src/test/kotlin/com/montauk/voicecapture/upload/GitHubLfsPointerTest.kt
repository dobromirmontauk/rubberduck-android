package com.montauk.voicecapture.upload

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GitHubLfsPointerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("lfs-pointer-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `computeOid matches a known sha256 and size`() {
        val content = "hello voice-vault\n".toByteArray(Charsets.UTF_8)
        val file = File(tempDir, "audio.ogg").apply { writeBytes(content) }

        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

        val oid = GitHubLfsPointer.computeOid(file)

        assertEquals(expectedSha256, oid.sha256Hex)
        assertEquals(content.size.toLong(), oid.sizeBytes)
    }

    @Test
    fun `computeOid handles empty files`() {
        val file = File(tempDir, "empty.ogg").apply { writeBytes(ByteArray(0)) }
        val emptySha256 = MessageDigest.getInstance("SHA-256").digest(ByteArray(0)).joinToString("") { "%02x".format(it) }

        val oid = GitHubLfsPointer.computeOid(file)

        assertEquals(emptySha256, oid.sha256Hex)
        assertEquals(0L, oid.sizeBytes)
    }

    @Test
    fun `pointerText matches the git-lfs spec v1 exactly`() {
        val oid = GitHubLfsPointer.Oid(
            sha256Hex = "4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393",
            sizeBytes = 12345L,
        )

        val text = GitHubLfsPointer.pointerText(oid)

        assertEquals(
            "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\n" +
                "size 12345\n",
            text,
        )
    }
}
