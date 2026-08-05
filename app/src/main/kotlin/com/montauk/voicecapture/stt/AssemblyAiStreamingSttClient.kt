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

        // Bead vn-edu.45 follow-up ("can we have it take less than 60s?"):
        // end-of-turn tuning for monologue capture, third lever alongside
        // vn-edu.43 (scroll pinning) and vn-edu.45's own word-level-stable
        // rendering -- the UI/tags never *depend* on turn closure anymore,
        // but a turn that closes sooner still means more (and smaller)
        // `live-transcript.jsonl` lines instead of one multi-minute blob.
        // Exact param names/defaults verified against AssemblyAI's current
        // v3 streaming websocket AsyncAPI reference
        // (https://www.assemblyai.com/docs/streaming/api-spec/streaming-websocket)
        // -- NOT the same names as an earlier draft of this comment guessed
        // (`min_end_of_turn_silence_when_confident` doesn't exist; the real
        // parameter is `min_turn_silence`).
        //
        // These two values are the ones that survived real before/after
        // measurement against the live endpoint (drive-home-hiring.wav, 225s,
        // via AssemblyAiLiveStreamingTest, four separate live runs -- see
        // that test's own KDoc and the commit message for the exact numbers).
        // Untuned defaults measured 8 finals (2.13/min) once; this
        // configuration (END_OF_TURN_CONFIDENCE_THRESHOLD + MAX_TURN_SILENCE_MS,
        // `min_turn_silence` deliberately left unset) measured 8 finals once
        // and 9 finals (2.39/min) once across two runs, both at the same
        // 0.984 word-overlap transcription quality as the baseline -- i.e.
        // AssemblyAI's own end-of-turn model has real run-to-run variance
        // even for identical audio+params, so this reads as "at least as
        // good, sometimes modestly better," not a dramatic, guaranteed win.
        // Explicitly pinning `min_turn_silence=400` alongside the other two
        // was ALSO tried and measured worse both times (6 finals / 1.59/min
        // clean, plus one run with a mid-stream reconnect that further
        // scrambled ordering) -- its true un-set default is evidently
        // already reasonable for this use case, and overriding it was
        // actively counter-productive, so it's deliberately left unset here.
        //
        // Default 0.4 assumes natural speech's pitch/pacing cues to gauge
        // "is this really the end of a thought." Synthesized (TTS) fixtures
        // like drive-home-hiring.wav have flat prosody, so that confidence
        // signal is weak. Lowering the bar trades a little precision (a turn
        // might close on a shorter-than-ideal pause) for closing more
        // reliably on the comma-level micro-pauses this kind of narration
        // actually has.
        const val END_OF_TURN_CONFIDENCE_THRESHOLD = 0.25
        // Hard ceiling: silence past this always force-closes the turn
        // regardless of confidence -- default is ~1280-1536ms depending on
        // configuration; 1000ms means *any* ~1s natural pause closes the
        // turn even in the worst case where the confidence-based check never
        // fires at all (the exact failure mode flat-prosody TTS risks).
        //
        // What this can't do: a genuinely continuous, unbroken stretch of
        // speech with NO acoustic pause at all (verified on this same
        // fixture -- amplitude-envelope analysis found only 3 real gaps
        // >=400ms in the whole 225s file) still runs to whatever length that
        // stretch takes to speak, tuned or not -- these two runs both closed
        // their first ~60s-long turn at essentially the same point (the
        // fixture's own first scripted silence, not a tunable cap). That's
        // exactly why the UI/tag fixes (vn-edu.43/44/45's actual rendering
        // and scoring changes) don't depend on this tuning at all.
        const val MAX_TURN_SILENCE_MS = 1_000
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
     * Bead vn-edu.45 follow-up: tunes end-of-turn detection for monologue
     * capture (see [END_OF_TURN_CONFIDENCE_THRESHOLD]/[MAX_TURN_SILENCE_MS]'s
     * own KDoc for the exact rationale and measured before/after numbers).
     * This is a third, complementary lever, not the fix -- the UI
     * ([TranscriptPartial.stableText] painting solid mid-turn, bead
     * vn-edu.45's main change) and the tag scorer (bead vn-edu.44's
     * [com.montauk.voicecapture.tags.TagCoordinator.onTick]) never depend on
     * how soon `end_of_turn` actually fires. Tuning this down still matters
     * for `live-transcript.jsonl`'s own per-turn granularity: shorter turns
     * mean smaller, more frequent final lines instead of one multi-minute
     * blob per WAL segment.
     */
    private suspend fun openSocket() {
        connectionStateFlow.value = SttConnectionState.CONNECTING
        val ready = CompletableDeferred<Unit>()
        val url = "$endpointBase?sample_rate=$sampleRateHz&encoding=pcm_s16le&format_turns=true" +
            "&end_of_turn_confidence_threshold=$END_OF_TURN_CONFIDENCE_THRESHOLD" +
            "&max_turn_silence=$MAX_TURN_SILENCE_MS"
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
