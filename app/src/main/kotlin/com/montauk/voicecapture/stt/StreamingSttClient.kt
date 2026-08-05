package com.montauk.voicecapture.stt

import kotlinx.coroutines.flow.Flow

/**
 * A partial transcript update from the streaming STT backend.
 *
 * `isFinal` distinguishes a segment the backend has locked in (won't be
 * revised further) from one that may still be corrected as more audio
 * arrives -- the UI renders both, but only appends `isFinal` segments to
 * `live-transcript.jsonl`.
 */
data class TranscriptPartial(
    val text: String,
    val isFinal: Boolean,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Stub for the real-time speech-to-text connection.
 *
 * Not implemented yet -- wired up in a follow-up against AssemblyAI's
 * Universal-Streaming API (16 kHz PCM in over a WebSocket, partial/final
 * transcript events back: https://www.assemblyai.com/docs/speech-to-text/universal-streaming).
 * [AudioEngine] will tee its PCM frames to [sendPcm] in addition to writing
 * them to the WAL, so recording and transcription are independent consumers
 * of the same capture loop -- STT dropping out never blocks or slows the
 * write-ahead log.
 */
interface StreamingSttClient {
    /** Opens the streaming connection. Suspends until the session handshake completes. */
    suspend fun connect(sampleRateHz: Int, channelCount: Int)

    /** Sends one chunk of 16-bit PCM audio for transcription. Fire-and-forget from the caller's perspective. */
    fun sendPcm(pcm: ByteArray, offset: Int, length: Int)

    /** Partial and final transcript segments as they arrive from the backend. */
    fun partials(): Flow<TranscriptPartial>

    /** Closes the connection, flushing any final partials first. */
    suspend fun close()
}

/** No-op placeholder so the rest of the app can depend on [StreamingSttClient] before the real client exists. */
class NoOpStreamingSttClient : StreamingSttClient {
    override suspend fun connect(sampleRateHz: Int, channelCount: Int) = Unit
    override fun sendPcm(pcm: ByteArray, offset: Int, length: Int) = Unit
    override fun partials(): Flow<TranscriptPartial> = kotlinx.coroutines.flow.emptyFlow()
    override suspend fun close() = Unit
}
