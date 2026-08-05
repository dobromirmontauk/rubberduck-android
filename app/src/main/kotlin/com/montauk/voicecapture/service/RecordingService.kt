package com.montauk.voicecapture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.montauk.voicecapture.BuildConfig
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.audio.AudioEngine
import com.montauk.voicecapture.audio.AudioSource
import com.montauk.voicecapture.audio.FileAudioSource
import com.montauk.voicecapture.audio.MicAudioSource
import com.montauk.voicecapture.audio.MicLevelMeter
import com.montauk.voicecapture.session.LiveTranscriptLine
import com.montauk.voicecapture.session.LiveTranscriptWriter
import com.montauk.voicecapture.session.ModeChange
import com.montauk.voicecapture.session.ModeEventWriter
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.session.RecordingModeStateMachine
import com.montauk.voicecapture.session.SessionHandle
import com.montauk.voicecapture.session.SessionModeEntry
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.stt.StreamingSttClient
import com.montauk.voicecapture.tags.TagCoordinator
import com.montauk.voicecapture.tags.TagsEventWriter
import com.montauk.voicecapture.upload.UploadWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.util.Date
import kotlin.concurrent.thread

/**
 * Foreground service that owns the [AudioEngine] for the lifetime of a
 * recording. Started only from a direct user tap (record button) -- Android
 * 14+ forbids starting a microphone foreground service from the background.
 *
 * The persistent notification is the user's only always-visible confirmation
 * that recording is still alive during a 30-60 minute walk/drive, so it shows
 * live elapsed time and a Stop action.
 */
class RecordingService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.montauk.voicecapture.action.START"
        const val ACTION_STOP = "com.montauk.voicecapture.action.STOP"
        const val ACTION_SET_MODE = "com.montauk.voicecapture.action.SET_MODE"
        const val EXTRA_MODE = "mode"

        /**
         * Debug-only (bead vn-edu.20): a value here on the [ACTION_START]
         * intent swaps [MicAudioSource] for a [FileAudioSource] reading the
         * given path/asset instead -- see [createAudioSource]. Ignored
         * entirely in release builds. Two ways to set it, both documented in
         * README.md's testing section: an `adb shell am start-foreground-service`
         * extra pointing at a file pushed to the device, or the "New Session"
         * bottom-nav tab's long-press fixture picker (debug builds only).
         */
        const val EXTRA_INJECT_AUDIO = "inject_audio"

        /** Prefix on [EXTRA_INJECT_AUDIO] meaning "read from debug assets/fixtures/<rest>", not a filesystem path. */
        const val ASSET_PREFIX = "asset:"

        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 1_000L
        // MediaMuxer's native remux path can, on a bad stream, block forever
        // rather than throw (see the watchdog note in endRecording()) --
        // generous enough for a real WAL, short enough that the UI/foreground
        // service never looks stuck to the user.
        private const val FINALIZE_TIMEOUT_MS = 20_000L
        // Generous relative to how rarely final lines actually arrive (at
        // most a few per minute of speech) -- this is slack for the pump to
        // catch up after one slow scorer call, not a real backpressure limit.
        private const val TAG_LINE_CHANNEL_CAPACITY = 32
        // Comfortably longer than AnthropicTagScorer's own 15s read timeout,
        // so a well-behaved scorer call always gets to finish and write its
        // event line before this gives up waiting on it.
        private const val TAG_PUMP_SHUTDOWN_TIMEOUT_MS = 20_000L

        fun startIntent(context: Context, injectAudio: String? = null): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START).apply {
                if (injectAudio != null) putExtra(EXTRA_INJECT_AUDIO, injectAudio)
            }
        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
        fun setModeIntent(context: Context, mode: RecordingMode): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_SET_MODE).putExtra(EXTRA_MODE, mode.wireValue)
    }

    private lateinit var audioEngine: AudioEngine
    private var currentSession: SessionHandle? = null
    private var startElapsedRealtimeMs: Long = 0L
    private var sttClient: StreamingSttClient? = null
    private var sttJobs: List<Job> = emptyList()

    /**
     * One event per thing [tagPumpJob] should feed into [TagCoordinator]:
     * either a newly-finalized transcript line, or a recording-time tick
     * (bead vn-edu.44) carrying whatever partial is currently open --
     * [startTicker] sends one of the latter every second regardless of
     * whether any turn has closed, which is what lets [TagCoordinator.onTick]
     * score a long-running monologue that never produces a [FinalLine].
     */
    private sealed interface TagPumpEvent {
        /** Recording-elapsed ms this event corresponds to, for the `tags` event line's timestamp. */
        val atMs: Long
        data class FinalLine(val text: String, override val atMs: Long) : TagPumpEvent
        data class Tick(val partialText: String, override val atMs: Long) : TagPumpEvent
    }

    /**
     * Decouples tag scoring (bead vn-edu.38) from the STT partials collector:
     * [startSttPipeline] and [startTicker] only ever do a non-blocking
     * [Channel.trySend] here, same pattern as
     * [com.montauk.voicecapture.stt.AssemblyAiStreamingSttClient.sendPcm]'s
     * `pcmChannel` -- a slow or hung [com.montauk.voicecapture.tags.TagScorer]
     * call (e.g. Anthropic over the network) can only delay this queue's own
     * drain, never the transcript pane, `live-transcript.jsonl` writes, or the
     * audio capture path.
     */
    private var tagLineChannel: Channel<TagPumpEvent>? = null
    private var tagPumpJob: Job? = null
    private var tagCoordinator: TagCoordinator? = null

    /** Mode history for the session currently recording (or just finished) -- reset in [beginRecording]. */
    private var modeStateMachine = RecordingModeStateMachine()

    /** Decides the "(silence)" hint independent of STT connection state -- see class KDoc there. */
    private val silenceDetector = SilenceDetector()

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioEngine()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> beginRecording(intent.getStringExtra(EXTRA_INJECT_AUDIO))
            ACTION_STOP -> endRecording()
            ACTION_SET_MODE -> setMode(intent)
        }
        return START_NOT_STICKY
    }

    private fun beginRecording(injectAudioSpec: String?) {
        if (currentSession != null) return // already recording

        val app = application as VoiceCaptureApp
        val session = app.sessionStore.createSession(Date())
        currentSession = session

        TranscriptStateHolder.reset()
        TagsStateHolder.reset()
        silenceDetector.reset()
        modeStateMachine = RecordingModeStateMachine()

        startTagPipeline(app, session)

        // Set before audioEngine.start() so the very first mic-level window
        // has a correct (near-zero) baseline instead of measuring against the
        // stale value from whatever this field last held.
        startElapsedRealtimeMs = SystemClock.elapsedRealtime()

        val stt = app.newSttClient()
        sttClient = stt
        val micLevelMeter = MicLevelMeter(sampleRateHz = AudioEngine.DEFAULT_SAMPLE_RATE_HZ) { level ->
            val now = SystemClock.elapsedRealtime()
            val silent = silenceDetector.isSilent(nowMs = now, recordingStartMs = startElapsedRealtimeMs, currentRms = level)
            TranscriptStateHolder.update { it.copy(micLevel = level, silenceHintVisible = silent) }
        }
        // Tee raw PCM to STT and the mic-level meter alongside (never instead
        // of) the WAL write below -- both are cheap/non-blocking, so a slow or
        // dead STT connection can never stall or lose audio capture.
        audioEngine.onPcmFrame = { pcm, length ->
            stt.sendPcm(pcm, 0, length)
            micLevelMeter.onPcmFrame(pcm, length)
        }

        val audioSource = createAudioSource(injectAudioSpec)
        val walFile = app.sessionStore.walFile(session.dir)
        audioEngine.start(walFile, audioSource)
        // Drives the recording screen's source chip -- "FILE" when injecting
        // (bead vn-edu.20), otherwise the existing bluetooth/phone-mic chip
        // logic in RecordingScreen/ChipsRow takes over.
        TranscriptStateHolder.update { it.copy(sourceLabel = audioSource.deviceLabel) }
        startSttPipeline(stt, session)

        RecordingStateHolder.update {
            it.copy(isRecording = true, sessionId = session.sessionId, elapsedMs = 0L, mode = modeStateMachine.currentMode)
        }
        // Every mode change, including the initial one at session start, is an
        // event line in live-transcript.jsonl -- see RecordingModeStateMachine.
        writeModeEvent(session, modeStateMachine.history.single())

        startForeground(NOTIFICATION_ID, buildNotification(elapsedMs = 0L))
        startTicker()
    }

    /**
     * [injectAudioSpec] is [EXTRA_INJECT_AUDIO] off the start intent -- non-null
     * only when the adb-injection path or the debug fixture picker set it (see
     * companion KDoc). `!BuildConfig.DEBUG` makes this dead code in release
     * builds regardless of what a crafted intent might carry: an exported
     * activity/service extra is not a trustworthy gate on its own.
     */
    private fun createAudioSource(injectAudioSpec: String?): AudioSource {
        if (!BuildConfig.DEBUG || injectAudioSpec.isNullOrBlank()) return MicAudioSource()
        return FileAudioSource(openStream = { openInjectedAudioStream(injectAudioSpec) })
    }

    private fun openInjectedAudioStream(spec: String): InputStream =
        if (spec.startsWith(ASSET_PREFIX)) {
            assets.open("fixtures/${spec.removePrefix(ASSET_PREFIX)}")
        } else {
            File(spec).inputStream()
        }

    /** Handles [ACTION_SET_MODE]: switchable mid-session, a no-op unless the mode actually changes. */
    private fun setMode(intent: Intent) {
        val session = currentSession ?: return
        val wireValue = intent.getStringExtra(EXTRA_MODE) ?: return
        val mode = RecordingMode.fromWireValue(wireValue) ?: return
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        val change = modeStateMachine.select(mode, atMs = elapsedMs) ?: return
        RecordingStateHolder.update { it.copy(mode = change.mode) }
        writeModeEvent(session, change)
    }

    private fun writeModeEvent(session: SessionHandle, change: ModeChange) {
        val app = application as VoiceCaptureApp
        lifecycleScope.launch(Dispatchers.IO) {
            app.sessionStore.transcriptFile(session.dir).appendText(ModeEventWriter.encodeLine(change) + "\n")
        }
    }

    /** Connects the STT client and fans its output into the UI state + `live-transcript.jsonl`. */
    private fun startSttPipeline(stt: StreamingSttClient, session: SessionHandle) {
        val app = application as VoiceCaptureApp
        val statusJob = lifecycleScope.launch {
            stt.connectionState().collect { state ->
                TranscriptStateHolder.update { it.copy(connectionState = state) }
            }
        }
        val partialsJob = lifecycleScope.launch {
            stt.partials().collect { partial ->
                // Any non-blank partial or final counts as activity -- clears the
                // "(silence)" hint instantly rather than waiting for the next
                // mic-level window (~100ms later) to re-evaluate it.
                if (partial.text.isNotBlank()) {
                    silenceDetector.onTranscriptActivity(SystemClock.elapsedRealtime())
                }
                TranscriptStateHolder.update { ui ->
                    val cleared = if (partial.text.isNotBlank()) ui.copy(silenceHintVisible = false) else ui
                    if (partial.isFinal) {
                        cleared.copy(
                            finalLines = cleared.finalLines + TranscriptLine(partial.text, partial.startMs, partial.endMs),
                            currentPartial = "",
                            partialStableText = "",
                            partialUnstableTail = "",
                        )
                    } else {
                        // Bead vn-edu.45: thread the word-level-final split
                        // through so the pane can paint stabilized words
                        // solid without waiting for end_of_turn.
                        cleared.copy(
                            currentPartial = partial.text,
                            partialStableText = partial.stableText,
                            partialUnstableTail = partial.unstableTail,
                        )
                    }
                }
                // Ingest contract: live-transcript.jsonl carries only immutable
                // (isFinal) segments -- partials are UI-only, never written to disk.
                if (partial.isFinal && partial.text.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        val line = LiveTranscriptLine(partial.startMs, partial.endMs, partial.text, final = true)
                        app.sessionStore.transcriptFile(session.dir).appendText(LiveTranscriptWriter.encodeLine(line) + "\n")
                    }
                    // Non-blocking hand-off (bead vn-edu.38) -- see tagLineChannel's
                    // KDoc. DROP_OLDEST means a saturated tag pump (e.g. a slow
                    // AnthropicTagScorer call) sheds old lines rather than ever
                    // making this collector wait.
                    tagLineChannel?.trySend(TagPumpEvent.FinalLine(partial.text, partial.endMs))
                }
            }
        }
        val connectJob = lifecycleScope.launch {
            runCatching { stt.connect(AudioEngine.DEFAULT_SAMPLE_RATE_HZ, channelCount = 1) }
        }
        sttJobs = listOf(statusJob, partialsJob, connectJob)
    }

    /**
     * Starts the tag-scoring pipeline (bead vn-edu.38): a dedicated pump
     * coroutine drains [tagLineChannel] serially (so [TagCoordinator]/
     * [com.montauk.voicecapture.tags.TagTracker], neither of which is
     * thread-safe, only ever see one event at a time) and, whenever the
     * displayed-tags set actually changes, publishes it to [TagsStateHolder]
     * and appends a `tags` event line to `live-transcript.jsonl` -- the
     * exact same "publish to UI state, then persist" order [writeModeEvent]
     * uses for mode changes.
     *
     * [TagPumpEvent.FinalLine] (from [startSttPipeline]) and
     * [TagPumpEvent.Tick] (from [startTicker], bead vn-edu.44) both land on
     * this same channel/pump so [TagCoordinator] is only ever driven from one
     * coroutine, matching [RecordingModeStateMachine]'s single-owner pattern.
     */
    private fun startTagPipeline(app: VoiceCaptureApp, session: SessionHandle) {
        val coordinator = TagCoordinator(app.newTagScorer())
        tagCoordinator = coordinator
        val channel = Channel<TagPumpEvent>(capacity = TAG_LINE_CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        tagLineChannel = channel
        tagPumpJob = lifecycleScope.launch(Dispatchers.IO) {
            for (event in channel) {
                val changed = runCatching {
                    when (event) {
                        is TagPumpEvent.FinalLine -> coordinator.onFinalLine(event.text, event.atMs)
                        is TagPumpEvent.Tick -> coordinator.onTick(event.atMs, event.partialText)
                    }
                }.getOrNull() ?: continue
                TagsStateHolder.update(changed)
                app.sessionStore.transcriptFile(session.dir).appendText(TagsEventWriter.encodeLine(event.atMs, changed) + "\n")
            }
        }
    }

    /**
     * Drives elapsed-time UI/notification updates every second, and (bead
     * vn-edu.44) also feeds [TagCoordinator] a [TagPumpEvent.Tick] carrying
     * whatever partial is currently open on every tick -- this is what lets a
     * long-running monologue that never closes a turn (no `end_of_turn` for
     * 45-60s+) still reach the scorer instead of waiting on a
     * [TagPumpEvent.FinalLine] that may not arrive for a minute or more.
     * Reads [TranscriptStateHolder] directly rather than threading the
     * partial text through a separate field -- that's already the single
     * source of truth [RecordingScreen][com.montauk.voicecapture.ui.RecordingScreen]
     * itself renders from.
     */
    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive && currentSession != null) {
                val elapsed = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
                RecordingStateHolder.update { it.copy(elapsedMs = elapsed) }
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(elapsed))
                tagLineChannel?.trySend(TagPumpEvent.Tick(TranscriptStateHolder.state.value.currentPartial, elapsed))
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun endRecording() {
        val session = currentSession ?: return
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        currentSession = null

        audioEngine.stop()
        audioEngine.onPcmFrame = null

        val app = application as VoiceCaptureApp
        val stt = sttClient
        sttClient = null
        val jobsToCancel = sttJobs
        sttJobs = emptyList()

        // Closing (not just cancelling) the channel lets the pump finish
        // writing out whatever it's already mid-scoring before the for-loop
        // over the channel ends on its own -- cancelling the job outright
        // could otherwise cut off a tags event line that was about to land.
        tagLineChannel?.close()
        tagLineChannel = null
        val tagJobToJoin = tagPumpJob
        tagPumpJob = null
        tagCoordinator = null

        lifecycleScope.launch {
            // Close the STT socket in parallel with the (independent) audio
            // finalize -- neither should wait on the other.
            val sttCloseJob = stt?.let { launch { runCatching { it.close() } } }
            // Bounded wait: a hung scorer call must never delay the service
            // from stopping -- see TagCoordinator/AnthropicTagScorer's own
            // "never blocks" contract; this is just defense in depth.
            val tagPumpCloseJob = tagJobToJoin?.let { launch { withTimeoutOrNull(TAG_PUMP_SHUTDOWN_TIMEOUT_MS) { it.join() } } }

            val finalizeSucceeded = runFinalizeWithWatchdog(app, session, elapsedMs)
            // No GitHub token configured (bead vn-edu.29: recording is never gated on
            // sign-in) means there's no uploader to hand this to -- runFinalizeWithWatchdog
            // already left the session at its writeMeta default of LOCAL in that case, and
            // it stays there until UploadWorker.enqueueBacklog drains it on a later sign-in.
            if (finalizeSucceeded && app.isGithubTokenConfigured()) {
                UploadWorker.enqueue(applicationContext, session.sessionId)
            }
            if (finalizeSucceeded) {
                launchTitleGeneration(app, session.sessionId)
            }
            // A false/timed-out result deliberately leaves audio.wal in place
            // with no meta.json written -- SessionStore.findUnfinalizedSessions()
            // picks it up as an orphaned recording and VoiceCaptureApp retries
            // the remux on next app launch. The session simply won't appear
            // in the list until then, per docs/audio-wal.md's recovery model.

            sttCloseJob?.join()
            tagPumpCloseJob?.join()
            jobsToCancel.forEach { it.cancel() }

            RecordingStateHolder.update { it.copy(isRecording = false, elapsedMs = elapsedMs) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Runs WAL-to-ogg finalization + meta.json write on its own thread and
     * waits on it with a timeout, rather than just dispatching onto
     * [Dispatchers.IO] and hoping for the best. This distinction matters:
     * [AudioEngine.finalizeToOgg] ends in a native `MediaMuxer.stop()` call,
     * and a native call blocked inside a coroutine doesn't respond to
     * coroutine cancellation -- cancellation only takes effect at a
     * suspension point, which a synchronous native call never reaches. Only
     * waiting on a *separate* thread via a genuinely suspending
     * [CompletableDeferred.await] lets [withTimeoutOrNull] actually give up
     * and let the service move on, even if that background thread stays
     * stuck. The leaked thread is an acceptable tradeoff against the UI
     * hanging forever -- see the incident this guards against in the
     * `AudioEngine.probeOpusCodecConfig` fix.
     */
    private suspend fun runFinalizeWithWatchdog(app: VoiceCaptureApp, session: SessionHandle, elapsedMs: Long): Boolean {
        val outcome = CompletableDeferred<Boolean>()
        thread(name = "finalize-${session.sessionId}") {
            val result = runCatching {
                val walFile = app.sessionStore.walFile(session.dir)
                val oggFile = app.sessionStore.oggFile(session.dir)
                audioEngine.finalizeToOgg(walFile, oggFile)
                walFile.delete()
                app.sessionStore.writeMeta(
                    handle = session,
                    durationMs = elapsedMs,
                    deviceModel = Build.MODEL,
                    appVersion = app.appVersionName(),
                    modes = modeStateMachine.history.map { SessionModeEntry(it.tMs, it.mode.wireValue) },
                )
                // writeMeta already defaulted this to LOCAL; only promote to QUEUED when
                // there's an actual GitHub token to upload against (see the comment at the
                // enqueue call site in endRecording for the LOCAL-stays-LOCAL case).
                if (app.isGithubTokenConfigured()) {
                    app.sessionStore.setUploadState(session.dir, UploadState.QUEUED)
                }
            }
            result.onFailure { e -> Log.e(TAG, "finalize failed for ${session.sessionId}", e) }
            outcome.complete(result.isSuccess)
        }

        val finished = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { outcome.await() }
        if (finished == null) {
            Log.e(
                TAG,
                "finalize timed out after ${FINALIZE_TIMEOUT_MS}ms for ${session.sessionId}; " +
                    "giving up waiting so the UI doesn't hang -- audio.wal is left for recovery on next launch",
            )
        }
        return finished == true
    }

    /**
     * Fires the post-finalize title generation (bead vn-edu.42) on
     * [VoiceCaptureApp.applicationScope], deliberately *not* this service's
     * own `lifecycleScope`: [endRecording] calls [stopSelf] shortly after
     * this returns, which would cancel a `lifecycleScope` child well before
     * a slow (up to [com.montauk.voicecapture.session.AnthropicTitleGenerator]'s
     * 5s timeout) call ever got to finish. Not joined by anything here --
     * "async" per the bead means this must never delay `stopSelf()`/the
     * upload enqueue above, so this call returns immediately regardless of
     * how long the generation task itself takes.
     *
     * Race note: [UploadWorker.enqueue] above can win the race and upload
     * meta.json before this coroutine finishes writing the title back to
     * it -- the bundle that leaves the device in that case simply has no
     * title yet. This is accepted, not fixed: no re-upload is triggered
     * when a title lands after the fact, so a title generated post-upload
     * stays local-only until some *other* re-upload happens to fire (e.g.
     * the detail screen's manual Re-upload action).
     */
    private fun launchTitleGeneration(app: VoiceCaptureApp, sessionId: String) {
        val generator = app.newTitleGenerator() ?: return // keyless: no task at all, matching today's behavior
        app.applicationScope.launch {
            val lines = app.sessionStore.readTranscriptLines(sessionId).map { it.text }
            val title = runCatching { generator.generate(lines) }.getOrNull()
            if (!title.isNullOrBlank()) {
                app.sessionStore.updateTitle(sessionId, title)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows elapsed time while a voice-capture session is recording"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(elapsedMs: Long): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val elapsedText = formatElapsed(elapsedMs)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Recording")
            .setContentText(elapsedText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
