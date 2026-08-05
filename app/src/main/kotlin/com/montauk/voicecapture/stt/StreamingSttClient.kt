package com.montauk.voicecapture.stt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Connection lifecycle of a [StreamingSttClient], surfaced to the UI as a status chip. */
enum class SttConnectionState {
    /** No API key configured -- recording works, live transcription simply never starts. */
    DISABLED,
    CONNECTING,
    CONNECTED,

    /** Socket dropped; a reconnect attempt is in flight or has exhausted its retries. */
    DROPPED,
}

/**
 * Real-time speech-to-text connection.
 *
 * Implemented against AssemblyAI's Universal-Streaming API (16 kHz PCM in
 * over a WebSocket, partial/final transcript events back:
 * https://www.assemblyai.com/docs/speech-to-text/universal-streaming) by
 * [AssemblyAiStreamingSttClient]. [AudioEngine] tees its PCM frames to
 * [sendPcm] in addition to writing them to the WAL, so recording and
 * transcription are independent consumers of the same capture loop -- STT
 * dropping out never blocks or slows the write-ahead log.
 */
interface StreamingSttClient {
    /** Opens the streaming connection. Suspends until the session handshake completes (or times out). */
    suspend fun connect(sampleRateHz: Int, channelCount: Int)

    /** Sends one chunk of 16-bit PCM audio for transcription. Fire-and-forget from the caller's perspective. */
    fun sendPcm(pcm: ByteArray, offset: Int, length: Int)

    /** Partial and final transcript segments as they arrive from the backend. */
    fun partials(): Flow<TranscriptPartial>

    /** Current connection status, for UI display. */
    fun connectionState(): StateFlow<SttConnectionState>

    /** Closes the connection, flushing any final partials first. */
    suspend fun close()
}

/** No-op placeholder used whenever no AssemblyAI API key is configured (see [SttClientFactory]). */
class NoOpStreamingSttClient : StreamingSttClient {
    private val state = MutableStateFlow(SttConnectionState.DISABLED)
    override suspend fun connect(sampleRateHz: Int, channelCount: Int) = Unit
    override fun sendPcm(pcm: ByteArray, offset: Int, length: Int) = Unit
    override fun partials(): Flow<TranscriptPartial> = kotlinx.coroutines.flow.emptyFlow()
    override fun connectionState(): StateFlow<SttConnectionState> = state.asStateFlow()
    override suspend fun close() = Unit
}

/** Builds the right [StreamingSttClient] for the current configuration. */
object SttClientFactory {
    /** [apiKey] is `BuildConfig.ASSEMBLYAI_API_KEY`; blank means "not configured". */
    fun create(apiKey: String): StreamingSttClient =
        if (apiKey.isBlank()) NoOpStreamingSttClient() else AssemblyAiStreamingSttClient(apiKey)
}
