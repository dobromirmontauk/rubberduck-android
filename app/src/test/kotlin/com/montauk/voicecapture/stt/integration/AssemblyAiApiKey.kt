package com.montauk.voicecapture.stt.integration

import java.io.File

/**
 * Resolves `ASSEMBLYAI_API_KEY` for the integration harness: process
 * environment first (matches the README's `ASSEMBLYAI_API_KEY=... ./gradlew`
 * override), falling back to the same dotfile local dev setups use so a key
 * doesn't need to be exported in every shell. Returns null (never throws) so
 * callers can [org.junit.Assume]-skip cleanly when nothing is configured.
 */
internal fun resolveAssemblyAiApiKey(): String? {
    System.getenv("ASSEMBLYAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    val envFile = File(System.getProperty("user.home"), ".config/voice-notes/env")
    if (!envFile.isFile) return null
    return envFile.readLines().firstNotNullOfOrNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("ASSEMBLYAI_API_KEY=")) {
            trimmed.substringAfter("=").trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
}
