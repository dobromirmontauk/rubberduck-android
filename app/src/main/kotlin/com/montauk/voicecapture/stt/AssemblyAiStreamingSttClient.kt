package com.montauk.voicecapture.stt

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * Streams 16-bit PCM audio to AssemblyAI's Universal-Streaming v3 WebSocket
 * API and surfaces `Turn` events as [TranscriptPartial]s.
 *
 * Design constraints from the capture pipeline this feeds:
 *  - [sendPcm] is called from [com.montauk.voicecapture.audio.AudioEngine]'s
 *    dedicated capture thread and must never block or throw -- it hands
 *    PCM off to a bounded channel and returns immediately. Under
 *    backpressure (slow network, socket mid-reconnect) frames are dropped
 *    here, never in the WAL write path.
 *  - A dropped socket triggers a bounded reconnect-with-backoff. Audio
 *    already sent before the drop is gone from AssemblyAI's perspective
 *    (no server-side resume in this API); this client simply keeps feeding
 *    live audio into the new connection once it's up. The gap shows up as
 *    a hole in `live-transcript.jsonl`, which is fine -- it's best-effort by
 *    contract; pass-2 transcription is the source of truth.
 */
class AssemblyAiStreamingSttClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    private val endpointBase: String = "wss://streaming.assemblyai.com/v3/ws",
) : StreamingSttClient {

    private companion object {
        const val TAG = "AssemblyAiStt"
        // ~a few seconds of audio at typical AudioRecord read-buffer sizes; deep
        // enough to ride out a brief reconnect, shallow enough that a stuck
        // socket doesn't quietly accumulate unbounded memory.
        const val PCM_CHANNEL_CAPACITY = 64
        // AssemblyAI wants 50-1000ms per binary frame; 100ms (@16kHz mono
        // 16-bit PCM = 3200 bytes) keeps live-transcript latency low while
        // comfortably clearing the floor.
        const val CHUNK_TARGET_BYTES = 3_200
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val RECONNECT_BASE_DELAY_MS = 500L
        const val RECONNECT_MAX_DELAY_MS = 8_000L
        const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val partialsFlow = MutableSharedFlow<TranscriptPartial>(extraBufferCapacity = 64)
    private val connectionStateFlow = MutableStateFlow(SttConnectionState.CONNECTING)
    private val pcmChannel = Channel<ByteArray>(
        capacity = PCM_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var webSocket: WebSocket? = null
    private var senderJob: Job? = null
    @Volatile private var sampleRateHz = 16_000
    @Volatile private var channelCount = 1
    @Volatile private var closed = false
    @Volatile private var reconnectAttempts = 0

    override suspend fun connect(sampleRateHz: Int, channelCount: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        closed = false
        openSocket()
        senderJob = scope.launch { pumpPcmToSocket() }
    }

    /**
     * Bead vn-edu.45 evaluated (but deliberately did not add) AssemblyAI's
     * end-of-turn query params here -- `end_of_turn_confidence_threshold`,
     * `min_end_of_turn_silence_when_confident`, and `max_turn_silence`
     * (https://www.assemblyai.com/docs/speech-to-text/universal-streaming#configuring-the-end-of-turn-detection).
     * Tuning those down would make `end_of_turn` fire sooner on a
     * continuous monologue, but they're a tradeoff, not a fix: too
     * aggressive and a mid-sentence pause (a breath, "um", swallowing) splits
     * one utterance into several finals, which is worse for the vault
     * ingest contract's per-turn `live-transcript.jsonl` lines than an
     * occasional long turn is for the UI. The actual fix for "why does it
     * stay partial that long" is this bead's real change: painting
     * word-level-stable text solid via [TranscriptPartial.stableText] while
     * the turn is still open, so the UX never depends on how soon
     * `end_of_turn` fires in the first place. Revisit these params only if a
     * *separate* problem shows up with turns staying open too long for
     * `live-transcript.jsonl`'s own per-turn granularity, not for rendering.
     */
    private suspend fun openSocket() {
        connectionStateFlow.value = SttConnectionState.CONNECTING
        val ready = CompletableDeferred<Unit>()
        val url = "$endpointBase?sample_rate=$sampleRateHz&encoding=pcm_s16le&format_turns=true"
        val request = Request.Builder()
            .url(url)
            // AssemblyAI's v3 endpoint expects the raw key here, no "Bearer" prefix.
            .addHeader("Authorization", apiKey)
            .build()
        webSocket = httpClient.newWebSocket(request, Listener(ready))
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        if (connected == null) {
            Log.w(TAG, "AssemblyAI connect handshake timed out after ${CONNECT_TIMEOUT_MS}ms")
            connectionStateFlow.value = SttConnectionState.DROPPED
        }
    }

    private inner class Listener(private val ready: CompletableDeferred<Unit>) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connectionStateFlow.value = SttConnectionState.CONNECTED
            reconnectAttempts = 0
            ready.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parseTurnMessage(text)?.let { partialsFlow.tryEmit(it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "AssemblyAI socket failure: ${t.message}")
            ready.complete(Unit) // don't hang connect() forever; reconnect loop takes over
            handleDrop()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) {
                Log.i(TAG, "AssemblyAI socket closed unexpectedly (code=$code reason=$reason)")
                handleDrop()
            }
        }
    }

    private fun handleDrop() {
        if (closed) return
        connectionStateFlow.value = SttConnectionState.DROPPED
        scope.launch { reconnectWithBackoff() }
    }

    private suspend fun reconnectWithBackoff() {
        while (!closed && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delayMs = (RECONNECT_BASE_DELAY_MS * (1L shl (reconnectAttempts - 1))).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            Log.i(TAG, "Reconnecting to AssemblyAI in ${delayMs}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
            delay(delayMs)
            if (closed) return
            runCatching { openSocket() }.onFailure { e ->
                Log.w(TAG, "Reconnect attempt $reconnectAttempts failed: ${e.message}")
            }
            if (connectionStateFlow.value == SttConnectionState.CONNECTED) return
        }
        if (!closed && connectionStateFlow.value != SttConnectionState.CONNECTED) {
            Log.w(TAG, "Giving up reconnecting to AssemblyAI after $reconnectAttempts attempts; live transcript stays off for the rest of this session")
        }
    }

    override fun sendPcm(pcm: ByteArray, offset: Int, length: Int) {
        if (closed) return
        // Copy out of the caller's (reused) buffer before handing off -- the
        // capture thread will overwrite `pcm` on its next read iteration.
        val copy = pcm.copyOfRange(offset, offset + length)
        pcmChannel.trySend(copy) // DROP_OLDEST overflow: never blocks, never throws
    }

    private suspend fun pumpPcmToSocket() {
        val buffer = ByteArrayOutputStream(CHUNK_TARGET_BYTES * 2)
        try {
            for (chunk in pcmChannel) {
                buffer.write(chunk)
                if (buffer.size() >= CHUNK_TARGET_BYTES) {
                    flushBuffer(buffer)
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // channel closed on close(); fall through to final flush below
        }
        flushBuffer(buffer)
    }

    private fun flushBuffer(buffer: ByteArrayOutputStream) {
        if (buffer.size() == 0) return
        val socket = webSocket
        if (socket != null && connectionStateFlow.value == SttConnectionState.CONNECTED) {
            socket.send(buffer.toByteArray().toByteString())
        }
        // Whether or not the send succeeded, drop this window's audio rather
        // than growing the buffer unboundedly while disconnected.
        buffer.reset()
    }

    override fun partials(): Flow<TranscriptPartial> = partialsFlow

    override fun connectionState(): StateFlow<SttConnectionState> = connectionStateFlow.asStateFlow()

    override suspend fun close() {
        closed = true
        pcmChannel.close()
        senderJob?.join()
        runCatching {
            webSocket?.send("{\"type\":\"Terminate\"}")
        }
        // Give the server a moment to flush the final Turn before we hang up.
        delay(300L)
        webSocket?.close(1000, "client closing")
        webSocket = null
    }
}
