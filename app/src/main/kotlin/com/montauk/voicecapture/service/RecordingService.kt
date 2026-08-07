package com.montauk.voicecapture.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.montauk.voicecapture.BuildConfig
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.audio.AudioEngine
import com.montauk.voicecapture.audio.AudioPauseMode
import com.montauk.voicecapture.audio.AudioSource
import com.montauk.voicecapture.audio.FileAudioSource
import com.montauk.voicecapture.audio.MicAudioSource
import com.montauk.voicecapture.audio.MicLevelMeter
import com.montauk.voicecapture.audio.VoiceActivityDetector
import com.montauk.voicecapture.audio.autoPauseFillFraction
import com.montauk.voicecapture.duck.BlinkHeartbeat
import com.montauk.voicecapture.duck.DuckPulse
import com.montauk.voicecapture.duck.DuckPulseStateHolder
import com.montauk.voicecapture.duck.nodPulseForFinalSegment
import com.montauk.voicecapture.session.LiveTranscriptLine
import com.montauk.voicecapture.session.LiveTranscriptWriter
import com.montauk.voicecapture.session.ModeChange
import com.montauk.voicecapture.session.ModeEventWriter
import com.montauk.voicecapture.session.PauseEventWriter
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.session.RecordingModeStateMachine
import com.montauk.voicecapture.session.SessionHandle
import com.montauk.voicecapture.session.SessionModeEntry
import com.montauk.voicecapture.session.UploadState
import com.montauk.voicecapture.settings.AppSecretsStore
import com.montauk.voicecapture.stt.StreamingSttClient
import com.montauk.voicecapture.stt.SttConnectionState
import com.montauk.voicecapture.stt.SttTimelineTracker
import com.montauk.voicecapture.summary.SummaryCoordinator
import com.montauk.voicecapture.summary.SummaryEventWriter
import com.montauk.voicecapture.summary.SummaryMarkdownWriter
import com.montauk.voicecapture.summary.SummaryNoteEventWriter
import com.montauk.voicecapture.tags.TagChipRail
import com.montauk.voicecapture.tags.TagCoordinator
import com.montauk.voicecapture.tags.TagsEventWriter
import com.montauk.voicecapture.tags.UserTagRef
import com.montauk.voicecapture.upload.UploadWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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

        // Bead asn-45m: user edits on the recording screen's tag rail --
        // add/remove/swap each land on their own action, same one-intent-
        // per-action shape as ACTION_SET_MODE above. EXTRA_TAG_NAME/EXTRA_TAG_ID
        // identify the tag being added or swapped-in; EXTRA_OLD_TAG_NAME is
        // only read by ACTION_TAG_SWAP, to say which chip is being replaced.
        const val ACTION_TAG_ADD = "com.montauk.voicecapture.action.TAG_ADD"
        const val ACTION_TAG_REMOVE = "com.montauk.voicecapture.action.TAG_REMOVE"
        const val ACTION_TAG_SWAP = "com.montauk.voicecapture.action.TAG_SWAP"
        // Bead asn-0jk: a single tap on a still-unapproved PROPOSED_NEW chip
        // approves it in place -- distinct action from ACTION_TAG_SWAP (which
        // still opens the picker for an already-EXISTING chip).
        const val ACTION_TAG_APPROVE = "com.montauk.voicecapture.action.TAG_APPROVE"
        const val EXTRA_TAG_NAME = "tag_name"
        const val EXTRA_TAG_ID = "tag_id"
        const val EXTRA_OLD_TAG_NAME = "old_tag_name"

        // Bead asn-rrw: the notes card's swipe gestures -- EXTRA_NOTE_TEXT is
        // the bullet text being acted on (must match SummaryCoordinator's
        // current newest bullet for ACTION_NOTE_DISCARD to actually do
        // anything -- see handleNoteDiscard).
        const val ACTION_NOTE_APPROVE = "com.montauk.voicecapture.action.NOTE_APPROVE"
        const val ACTION_NOTE_DISCARD = "com.montauk.voicecapture.action.NOTE_DISCARD"
        const val EXTRA_NOTE_TEXT = "note_text"

        /** Bead asn-r60: the manual (hard) pause button next to Stop. */
        const val ACTION_PAUSE = "com.montauk.voicecapture.action.PAUSE"

        /**
         * Bead asn-r60: an explicit resume tap -- the same pause button,
         * now reading "Resume", while either kind of pause is active (bead
         * asn-o63 dropped the separate auto-pause banner that used to offer
         * its own "tap to resume"; one control does both now). VAD can also
         * resume a soft/auto pause on its own without this action ever
         * firing; see [resumeTapped]'s KDoc for how both paths land in the
         * same place.
         */
        const val ACTION_RESUME = "com.montauk.voicecapture.action.RESUME"

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
        // Bead asn-evl: mirrors TAG_LINE_CHANNEL_CAPACITY/TAG_PUMP_SHUTDOWN_TIMEOUT_MS
        // for the summary pump -- generous enough to absorb one slow
        // AnthropicSummaryGenerator round (up to its own 20s read timeout)
        // without dropping a real recording-elapsed tick.
        private const val SUMMARY_TICK_CHANNEL_CAPACITY = 32
        private const val SUMMARY_PUMP_SHUTDOWN_TIMEOUT_MS = 25_000L

        /** Bead asn-r60: the two [RecordingActivityState] values in which live PCM should still be fed to STT. */
        private val STT_ACTIVE_STATES = setOf(RecordingActivityState.SPEAKING, RecordingActivityState.QUIET)

        /**
         * Bead asn-o63: consecutive speaking [MicLevelMeter] windows (~100ms
         * each) required before an auto-pause resumes -- see
         * [VoiceActivityDetector.consecutiveSpeechWindows]'s KDoc for the
         * live-test bug this fixes (a single blip window resuming a
         * 323ms-long auto-pause). 3 windows == ~300ms of sustained speech.
         */
        private const val RESUME_HYSTERESIS_WINDOWS = 3

        fun startIntent(context: Context, injectAudio: String? = null): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_START).apply {
                if (injectAudio != null) putExtra(EXTRA_INJECT_AUDIO, injectAudio)
            }
        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
        fun setModeIntent(context: Context, mode: RecordingMode): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_SET_MODE).putExtra(EXTRA_MODE, mode.wireValue)

        /** Bead asn-45m: the user tapped the rail's trailing (+) chip and picked/typed [tag] (optionally tree-matched, [tagId]) from the picker. */
        fun addTagIntent(context: Context, tag: String, tagId: String?): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_TAG_ADD)
                .putExtra(EXTRA_TAG_NAME, tag).putExtra(EXTRA_TAG_ID, tagId)

        /** Bead asn-45m: the user tapped a chip's ✕. */
        fun removeTagIntent(context: Context, tag: String): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_TAG_REMOVE).putExtra(EXTRA_TAG_NAME, tag)

        /** Bead asn-45m: the user tapped [oldTag]'s chip body and picked a replacement ([newTag]/[newTagId]) from the picker. */
        fun swapTagIntent(context: Context, oldTag: String, newTag: String, newTagId: String?): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_TAG_SWAP)
                .putExtra(EXTRA_OLD_TAG_NAME, oldTag).putExtra(EXTRA_TAG_NAME, newTag).putExtra(EXTRA_TAG_ID, newTagId)

        /** Bead asn-0jk: the user tapped a still-unapproved PROPOSED_NEW chip's body to approve [tag] in place. */
        fun approveTagIntent(context: Context, tag: String): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_TAG_APPROVE).putExtra(EXTRA_TAG_NAME, tag)

        fun pauseIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE)
        fun resumeIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_RESUME)

        /** Bead asn-rrw: the notes card's swipe-right on [noteText]. */
        fun approveNoteIntent(context: Context, noteText: String): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_NOTE_APPROVE).putExtra(EXTRA_NOTE_TEXT, noteText)

        /** Bead asn-rrw: the notes card's swipe-left on [noteText]. */
        fun discardNoteIntent(context: Context, noteText: String): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_NOTE_DISCARD).putExtra(EXTRA_NOTE_TEXT, noteText)
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

    /**
     * Bead asn-45m: the recording screen's editable tag rail state machine
     * -- owned here alongside [tagCoordinator] (same session-scoped
     * lifetime: created in [beginRecording], cleared in [endRecording]).
     * [startTagPipeline]'s pump feeds it every new suggested-tags set;
     * [handleTagAdd]/[handleTagRemove]/[handleTagSwap]/[handleTagApprove] feed it the user's
     * own edits. Either path republishes [TagChipRail.chips] to
     * [TagRailStateHolder] and, for a user edit only, appends a
     * user-attributed tags event line to `live-transcript.jsonl`.
     */
    private var tagChipRail: TagChipRail? = null

    /**
     * Bead asn-evl: the live rolling bullet summary's own tick channel/pump,
     * separate from [tagLineChannel] -- [SummaryCoordinator] only ever needs
     * a recording-elapsed tick (it reads the full transcript itself off
     * [TranscriptStateHolder] when a tick actually triggers a round), not
     * the per-final-line events tags care about. Same non-blocking
     * [Channel.trySend] decoupling rationale as [tagLineChannel]'s KDoc: a
     * slow/hung [com.montauk.voicecapture.summary.SummaryGenerator] call can
     * only delay this pump's own drain, never [startTicker]'s notification/
     * elapsed-time updates.
     */
    private var summaryTickChannel: Channel<Long>? = null
    private var summaryPumpJob: Job? = null
    private var summaryCoordinator: SummaryCoordinator? = null

    /** Mode history for the session currently recording (or just finished) -- reset in [beginRecording]. */
    private var modeStateMachine = RecordingModeStateMachine()

    /**
     * Bead vn-edu.56: true once [SttConnectionState.CONNECTED] has been
     * observed at least once during the current session -- NOT "is connected
     * right now", so a session that connected then dropped mid-way still
     * gets [TooShortPolicy]'s word-count check rather than being mistaken for
     * an offline/keyless one. Reset in [beginRecording]; read (then left
     * alone -- [endRecording] doesn't reset it, [beginRecording] does) in
     * [endRecording] via the local `sttWasConnected` snapshot.
     */
    private var sttEverConnected = false

    /** Decides the "(silence)" hint independent of STT connection state -- see class KDoc there. */
    private val silenceDetector = SilenceDetector()

    /** Bead asn-02h.1: throttles the duck's BLINK pulse to the transcription heartbeat -- see [BlinkHeartbeat]'s own KDoc. */
    private val blinkHeartbeat = BlinkHeartbeat()

    /**
     * Bead asn-r60: VAD-driven SPEAKING/QUIET classification, fed one RMS
     * window at a time from the [MicLevelMeter] set up in [beginRecording]
     * -- this is what [RecordingActivityStateHolder] and
     * the auto-pause countdown are both driven from, and it runs continuously
     * whether or not any pause is active (see [RecordingActivityState]'s
     * KDoc for why "VAD-driven even outside pause" matters to the
     * duck-animation consumer).
     */
    private val voiceActivityDetector = VoiceActivityDetector()

    /**
     * Bead asn-r60: keeps `live-transcript.jsonl` segment timestamps
     * monotonic across the deliberate STT reconnect a manual-pause resume
     * causes -- see its own KDoc for why only that reconnect (not
     * [com.montauk.voicecapture.stt.AssemblyAiStreamingSttClient]'s
     * pre-existing drop/retry reconnect) is in scope here.
     */
    private val sttTimelineTracker = SttTimelineTracker()

    /**
     * Bead asn-r60 (re-anchored by asn-o63): the trimmed-audio-timeline
     * basis for `live-transcript.jsonl`'s `mode`/`pause`/`resume` event
     * `t_ms` values, for `meta.json`'s `recorded_ms` field, AND (bead
     * asn-o63) for the on-screen big timer + foreground notification text --
     * paused across BOTH [RecordingActivityState.USER_PAUSED] and
     * [RecordingActivityState.AUTO_PAUSED] spans, matching exactly what
     * [audioEngine] actually persists to `audio.ogg` (see [AudioEngine]'s
     * cumulative-fed-bytes presentation timestamps). Bead asn-o63's live-test
     * feedback: the visible timer must show real recorded *content* time (10
     * min wall-clock with 2 min of actual speech reads "2:00"), not a clock
     * that only freezes for a hard pause -- so the UI timer now shares this
     * exact clock instead of a separate `visibleClock` that used to leave
     * auto-pause spans ticking. Deliberately NOT applied to the pre-existing
     * `tags`/summary event lines' own tick timestamp -- bead asn-k7i owns
     * that timing surface concurrently; see this service's `startTicker`
     * KDoc.
     */
    private val audioTimelineClock = PausableElapsedClock()

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
            ACTION_TAG_ADD -> handleTagAdd(intent)
            ACTION_TAG_REMOVE -> handleTagRemove(intent)
            ACTION_TAG_SWAP -> handleTagSwap(intent)
            ACTION_TAG_APPROVE -> handleTagApprove(intent)
            ACTION_NOTE_APPROVE -> handleNoteApprove(intent)
            ACTION_NOTE_DISCARD -> handleNoteDiscard(intent)
            ACTION_PAUSE -> pauseManually()
            ACTION_RESUME -> resumeTapped()
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
        TagRailStateHolder.reset()
        TagTreeStateHolder.reset()
        SummaryStateHolder.reset()
        silenceDetector.reset()
        blinkHeartbeat.reset()
        DuckPulseStateHolder.reset()
        modeStateMachine = RecordingModeStateMachine()
        sttEverConnected = false
        // Bead asn-r60: same "never leak a previous session's state"
        // discipline as the Bluetooth-route reset below.
        voiceActivityDetector.reset()
        sttTimelineTracker.reset()
        audioTimelineClock.reset()
        RecordingActivityStateHolder.reset()
        // Bead vn-edu.2: a previous session's Bluetooth route/warning/loss
        // flags must never leak into this new one -- createAudioSource's
        // onRouteChanged callback only ever sets these forward, it never
        // clears bluetoothDeviceLost, so a stale true from the last session
        // would otherwise stick around until the *next* device-list change.
        RecordingStateHolder.update {
            it.copy(activeAudioRoute = null, scoNarrowbandWarning = false, bluetoothDeviceLost = false)
        }

        startTagPipeline(app, session)
        startSummaryPipeline(app, session)

        // Set before audioEngine.start() so the very first mic-level window
        // has a correct (near-zero) baseline instead of measuring against the
        // stale value from whatever this field last held.
        startElapsedRealtimeMs = SystemClock.elapsedRealtime()

        val stt = app.newSttClient()
        sttClient = stt
        val micLevelMeter = MicLevelMeter(sampleRateHz = AudioEngine.DEFAULT_SAMPLE_RATE_HZ) { level ->
            val now = SystemClock.elapsedRealtime()
            val silent = silenceDetector.isSilent(nowMs = now, recordingStartMs = startElapsedRealtimeMs, currentRms = level)
            // Bead asn-r60: VAD runs off this same ~100ms RMS window
            // regardless of pause state (see AudioEngine.onPcmFrame's own
            // "always fires" contract) -- this is what lets SPEAKING/QUIET
            // stay genuinely live, decides when to fire auto-pause, and
            // detects an auto-resume. Updated before the TranscriptStateHolder
            // write below so quietDurationMs reflects this window already.
            voiceActivityDetector.onWindow(level, MicLevelMeter.DEFAULT_WINDOW_MS.toLong())
            TranscriptStateHolder.update {
                it.copy(
                    micLevel = level,
                    silenceHintVisible = silent,
                    quietDurationMs = voiceActivityDetector.continuousQuietMs,
                    autoPauseFillFraction = currentAutoPauseFillFraction(),
                )
            }
            onVadWindow()
        }
        // Tee raw PCM to STT and the mic-level meter alongside (never instead
        // of) the WAL write below -- both are cheap/non-blocking, so a slow or
        // dead STT connection can never stall or lose audio capture. Bead
        // asn-r60: reads the `sttClient` field fresh on every frame (not a
        // captured local) so a manual-pause resume's brand-new client is
        // picked up automatically, and gates the send on the *current*
        // activity state -- AUTO_PAUSED/USER_PAUSED both mean "don't feed STT
        // live", matching the bead's "stop STT streaming" requirement for
        // either kind of pause. AudioEngine's own pause gate independently
        // handles what happens to the *persisted* audio; this only concerns
        // the STT tee.
        audioEngine.onPcmFrame = { pcm, length ->
            micLevelMeter.onPcmFrame(pcm, length)
            if (RecordingActivityStateHolder.state.value in STT_ACTIVE_STATES) {
                sttClient?.sendPcm(pcm, 0, length)
            }
        }
        // Bead asn-r60: on an auto-resume, AudioEngine replays the buffered
        // ring audio right here -- mirror-feed the exact same chunks to STT
        // so the transcript keeps the onset of speech the buffer exists to
        // protect (the encoder/WAL side of this same flush is AudioEngine's
        // own responsibility, not this callback's).
        audioEngine.onRingBufferFlush = { chunks ->
            val liveStt = sttClient
            if (liveStt != null) {
                chunks.forEach { chunk -> liveStt.sendPcm(chunk, 0, chunk.size) }
            }
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
        if (!BuildConfig.DEBUG || injectAudioSpec.isNullOrBlank()) {
            val mic = MicAudioSource(
                audioManager = getSystemService(AudioManager::class.java),
                // Bead asn-b8u: lets MicAudioSource (a) watch for the real
                // SCO-connect broadcast with a bounded timeout instead of
                // optimistically assuming the link is live the instant
                // startBluetoothSco() returns, and (b) gate route selection
                // on BLUETOOTH_CONNECT actually being granted -- checked
                // live on every route evaluation rather than once at session
                // start, so a grant from Settings mid-session (or a denial
                // that only shows up later) is picked up immediately.
                context = this,
                hasBluetoothConnectPermission = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                },
            )
            // Bead vn-edu.2: publish every routing decision (initial pick,
            // Bluetooth upgrade/downgrade, or a mid-session device-loss
            // fallback to the phone mic) to the recording screen. Onto
            // RecordingStateHolder rather than TranscriptStateHolder because
            // this changes live, mid-session -- unlike TranscriptUiState.sourceLabel,
            // which is fixed for the whole session.
            mic.onRouteChanged = { transition ->
                RecordingStateHolder.update {
                    it.copy(
                        activeAudioRoute = transition.route.type,
                        scoNarrowbandWarning = transition.route.scoNarrowbandWarning,
                        bluetoothDeviceLost = it.bluetoothDeviceLost || transition.isDeviceLossFallback,
                    )
                }
            }
            return mic
        }
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
        val change = modeStateMachine.select(mode, atMs = audioTimelineElapsedMs()) ?: return
        RecordingStateHolder.update { it.copy(mode = change.mode) }
        writeModeEvent(session, change)
    }

    private fun writeModeEvent(session: SessionHandle, change: ModeChange) {
        val app = application as VoiceCaptureApp
        lifecycleScope.launch(Dispatchers.IO) {
            app.sessionStore.transcriptFile(session.dir).appendText(ModeEventWriter.encodeLine(change) + "\n")
        }
    }

    /** Handles [ACTION_TAG_ADD]: the (+) chip's picker (or its free-form "Add" row) confirmed a new tag. */
    private fun handleTagAdd(intent: Intent) {
        val session = currentSession ?: return
        val rail = tagChipRail ?: return
        val tag = intent.getStringExtra(EXTRA_TAG_NAME)?.takeIf { it.isNotBlank() } ?: return
        val tagId = intent.getStringExtra(EXTRA_TAG_ID)
        if (!rail.onAdd(tag, tagId)) return
        TagRailStateHolder.update(rail.chips())
        writeUserTagEvent(session, added = listOf(UserTagRef(tag, tagId)), removed = emptyList())
    }

    /** Handles [ACTION_TAG_REMOVE]: the user tapped a chip's ✕. */
    private fun handleTagRemove(intent: Intent) {
        val session = currentSession ?: return
        val rail = tagChipRail ?: return
        val tag = intent.getStringExtra(EXTRA_TAG_NAME)?.takeIf { it.isNotBlank() } ?: return
        if (!rail.onRemove(tag)) return
        TagRailStateHolder.update(rail.chips())
        writeUserTagEvent(session, added = emptyList(), removed = listOf(UserTagRef(tag)))
    }

    /** Handles [ACTION_TAG_SWAP]: the user tapped a chip's body and picked a replacement from the tree picker. */
    private fun handleTagSwap(intent: Intent) {
        val session = currentSession ?: return
        val rail = tagChipRail ?: return
        val oldTag = intent.getStringExtra(EXTRA_OLD_TAG_NAME)?.takeIf { it.isNotBlank() } ?: return
        val newTag = intent.getStringExtra(EXTRA_TAG_NAME)?.takeIf { it.isNotBlank() } ?: return
        val newTagId = intent.getStringExtra(EXTRA_TAG_ID)
        if (!rail.onSwap(oldTag, newTag, newTagId)) return
        TagRailStateHolder.update(rail.chips())
        writeUserTagEvent(session, added = listOf(UserTagRef(newTag, newTagId)), removed = listOf(UserTagRef(oldTag)))
    }

    /** Handles [ACTION_TAG_APPROVE] (bead asn-0jk): a single tap on a still-unapproved PROPOSED_NEW chip's body approves it in place, no picker involved. */
    private fun handleTagApprove(intent: Intent) {
        val session = currentSession ?: return
        val rail = tagChipRail ?: return
        val tag = intent.getStringExtra(EXTRA_TAG_NAME)?.takeIf { it.isNotBlank() } ?: return
        if (!rail.onApprove(tag)) return
        TagRailStateHolder.update(rail.chips())
        writeUserTagEvent(session, added = listOf(UserTagRef(tag, tagId = null, approved = true)), removed = emptyList())
    }

    /**
     * Appends a user-attributed tags event line (bead asn-45m) --
     * [TagsEventWriter.encodeUserEditLine]'s two-array shape, timestamped the
     * same way [setMode]/[writeModeEvent] timestamp a mode change: elapsed
     * recording time at the moment the intent was handled, not whatever
     * [event][TagPumpEvent]'s own `atMs` last was (a tag edit is a separate,
     * user-driven event, not a re-run of the scorer pump).
     */
    private fun writeUserTagEvent(session: SessionHandle, added: List<UserTagRef>, removed: List<UserTagRef>) {
        val app = application as VoiceCaptureApp
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        lifecycleScope.launch(Dispatchers.IO) {
            app.sessionStore.transcriptFile(session.dir).appendText(TagsEventWriter.encodeUserEditLine(elapsedMs, added, removed) + "\n")
        }
    }

    /**
     * Handles [ACTION_NOTE_APPROVE] (bead asn-rrw): the notes card's
     * swipe-right. Approving a bullet has no effect on [summaryCoordinator]'s
     * own state -- it was already a normal, filed bullet the moment it
     * landed -- this only records that the user explicitly signed off on it.
     */
    private fun handleNoteApprove(intent: Intent) {
        val session = currentSession ?: return
        val text = intent.getStringExtra(EXTRA_NOTE_TEXT)?.takeIf { it.isNotBlank() } ?: return
        writeUserNoteEvent(session, approved = text, discarded = null)
    }

    /**
     * Handles [ACTION_NOTE_DISCARD] (bead asn-rrw): the notes card's
     * swipe-left. [SummaryCoordinator.discardBullet] both removes [text] from
     * the running summary (so it's never filed) and remembers it for the
     * next round's generator context -- this then republishes
     * [SummaryStateHolder] and rewrites `summary.md` in full so the on-disk
     * bundle immediately reflects the removal, exactly the same "rewrite
     * whole" contract [startSummaryPipeline] uses for a normal round. A
     * mismatched [text] (the coordinator's newest bullet already moved on --
     * a stale/late discard tap) is a silent no-op: nothing is removed, no
     * event is written, same as [SummaryCoordinator.discardBullet]'s own
     * false-return contract.
     */
    private fun handleNoteDiscard(intent: Intent) {
        val session = currentSession ?: return
        val app = application as VoiceCaptureApp
        val coordinator = summaryCoordinator ?: return
        val text = intent.getStringExtra(EXTRA_NOTE_TEXT)?.takeIf { it.isNotBlank() } ?: return
        if (!coordinator.discardBullet(text)) return

        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        SummaryStateHolder.update(
            SummaryUiState(bullets = coordinator.currentBullets(), newestIndex = null, updatedAtMs = elapsedMs, stale = coordinator.isStale()),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            app.sessionStore.summaryFile(session.dir).writeText(SummaryMarkdownWriter.render(coordinator.currentBullets()))
        }
        writeUserNoteEvent(session, approved = null, discarded = text)
    }

    /** Appends a [SummaryNoteEventWriter] user-action line -- same timestamp convention as [writeUserTagEvent]: elapsed recording time at the moment the intent was handled. */
    private fun writeUserNoteEvent(session: SessionHandle, approved: String?, discarded: String?) {
        val app = application as VoiceCaptureApp
        val elapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        val line = when {
            approved != null -> SummaryNoteEventWriter.encodeApproved(elapsedMs, approved)
            discarded != null -> SummaryNoteEventWriter.encodeDiscarded(elapsedMs, discarded)
            else -> return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            app.sessionStore.transcriptFile(session.dir).appendText(line + "\n")
        }
    }

    /** [audioTimelineClock]'s current reading -- see that field's KDoc for exactly what it excludes. */
    private fun audioTimelineElapsedMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return audioTimelineClock.elapsedMs(now, now - startElapsedRealtimeMs)
    }

    /**
     * Bead asn-o63: current 0f..1f progress toward auto-pause -- thin
     * wrapper around the pure [com.montauk.voicecapture.audio.autoPauseFillFraction]
     * (see its own KDoc for the math and why it's a standalone testable
     * function) fed from this session's live [voiceActivityDetector] reading
     * and the current settings. Named distinctly from the top-level function
     * it wraps so an unqualified call here can't accidentally resolve to
     * itself instead of the pure function.
     */
    private fun currentAutoPauseFillFraction(): Float {
        val app = application as VoiceCaptureApp
        return autoPauseFillFraction(
            continuousQuietMs = voiceActivityDetector.continuousQuietMs,
            totalThresholdMs = app.secretsStore.autoPauseSilenceThresholdMs,
            fillDurationMs = AppSecretsStore.AUTO_PAUSE_FILL_DURATION_MS,
            enabled = app.secretsStore.autoPauseEnabled,
        )
    }

    /**
     * Bead asn-r60: VAD-driven activity-state machine, called from every RMS
     * window regardless of pause state (see [MicLevelMeter]'s callback in
     * [beginRecording], which updates [voiceActivityDetector] itself just
     * before calling this). Three cases:
     *  - [RecordingActivityState.USER_PAUSED]: VAD never auto-transitions out
     *    of a manual pause -- only an explicit resume tap does.
     *  - [RecordingActivityState.AUTO_PAUSED]: resumes once
     *    [RESUME_HYSTERESIS_WINDOWS] consecutive windows have read speech
     *    (bead asn-o63 -- see [VoiceActivityDetector.consecutiveSpeechWindows]'s
     *    KDoc for the single-window resume bug this replaces).
     *  - [RecordingActivityState.SPEAKING]/[RecordingActivityState.QUIET]:
     *    publish whichever VAD currently says, and fire auto-pause once
     *    [VoiceActivityDetector.continuousQuietMs] clears the (settings-tunable,
     *    default-on) threshold.
     */
    private fun onVadWindow() {
        val session = currentSession ?: return
        when (RecordingActivityStateHolder.state.value) {
            RecordingActivityState.USER_PAUSED -> Unit
            RecordingActivityState.AUTO_PAUSED -> {
                if (voiceActivityDetector.consecutiveSpeechWindows >= RESUME_HYSTERESIS_WINDOWS) {
                    resumeFromAutoPause(session)
                }
            }
            RecordingActivityState.SPEAKING, RecordingActivityState.QUIET -> {
                val speaking = voiceActivityDetector.isSpeaking
                RecordingActivityStateHolder.set(if (speaking) RecordingActivityState.SPEAKING else RecordingActivityState.QUIET)
                val app = application as VoiceCaptureApp
                if (!speaking &&
                    app.secretsStore.autoPauseEnabled &&
                    voiceActivityDetector.continuousQuietMs >= app.secretsStore.autoPauseSilenceThresholdMs
                ) {
                    enterAutoPause(session)
                }
            }
        }
    }

    /** Soft pause (bead asn-r60): mic stays open, [audioEngine] starts ring-buffering instead of persisting. */
    private fun enterAutoPause(session: SessionHandle) {
        RecordingActivityStateHolder.set(RecordingActivityState.AUTO_PAUSED)
        audioEngine.setPauseMode(AudioPauseMode.SOFT)
        audioTimelineClock.pause(SystemClock.elapsedRealtime())
        writePauseEvent(session, event = "pause", reason = "auto")
    }

    /**
     * VAD detected speech during an auto-pause: [AudioEngine.setPauseMode]'s
     * ACTIVE transition synchronously flushes the ring buffer (see its own
     * KDoc) -- both persisting that buffered audio and, via
     * [AudioEngine.onRingBufferFlush], mirror-feeding it to STT -- before
     * this returns.
     */
    private fun resumeFromAutoPause(session: SessionHandle) {
        audioTimelineClock.resume(SystemClock.elapsedRealtime())
        audioEngine.setPauseMode(AudioPauseMode.ACTIVE)
        RecordingActivityStateHolder.set(RecordingActivityState.SPEAKING)
        writePauseEvent(session, event = "resume", reason = "auto")
    }

    /**
     * Handles [ACTION_PAUSE]: hard mute (bead asn-r60) -- stop persisting,
     * retain no buffer, and actually close the STT stream rather than merely
     * muting it (frees the connection while paused, matches the bead's
     * literal "close/stop the AssemblyAI stream" wording). Still reachable
     * from [RecordingActivityState.AUTO_PAUSED] (escalating a soft pause to
     * a hard one) at this action/intent level -- [AudioPauseMode]'s
     * SOFT->HARD transition discards the tentative ring buffer rather than
     * persisting it, see [com.montauk.voicecapture.audio.CapturePauseGate]'s
     * KDoc -- though bead asn-o63's single pause/resume button no longer
     * triggers that path itself (tapping while auto-paused now always
     * resumes, matching its "Resume" label); this stays available for any
     * future UI that wants an explicit escalate affordance.
     */
    private fun pauseManually() {
        val session = currentSession ?: return
        if (RecordingActivityStateHolder.state.value == RecordingActivityState.USER_PAUSED) return
        val now = SystemClock.elapsedRealtime()
        RecordingActivityStateHolder.set(RecordingActivityState.USER_PAUSED)
        audioEngine.setPauseMode(AudioPauseMode.HARD)
        audioTimelineClock.pause(now)

        val oldStt = sttClient
        sttClient = null
        val oldJobs = sttJobs
        sttJobs = emptyList()
        lifecycleScope.launch {
            oldJobs.forEach { it.cancel() }
            runCatching { oldStt?.close() }
        }

        writePauseEvent(session, event = "pause", reason = "user")
    }

    /**
     * Handles [ACTION_RESUME]: a single explicit "resume" tap (the pause
     * button, now reading "Resume", while either kind of pause is active --
     * bead asn-o63) routed to whichever kind of pause is actually active. A
     * no-op while not paused at all.
     */
    private fun resumeTapped() {
        val session = currentSession ?: return
        when (RecordingActivityStateHolder.state.value) {
            RecordingActivityState.USER_PAUSED -> resumeManually(session)
            RecordingActivityState.AUTO_PAUSED -> resumeFromAutoPause(session)
            RecordingActivityState.SPEAKING, RecordingActivityState.QUIET -> Unit
        }
    }

    /**
     * The manual-pause half of [resumeTapped]. Opens a brand-new STT
     * connection (the old one was closed on pause) and folds its
     * reset-to-zero clock into [sttTimelineTracker] so
     * `live-transcript.jsonl` timestamps stay monotonic across the gap -- see
     * that class's KDoc.
     */
    private fun resumeManually(session: SessionHandle) {
        val now = SystemClock.elapsedRealtime()
        audioEngine.setPauseMode(AudioPauseMode.ACTIVE)
        audioTimelineClock.resume(now)
        sttTimelineTracker.onReconnect()

        val app = application as VoiceCaptureApp
        val stt = app.newSttClient()
        sttClient = stt
        startSttPipeline(stt, session)

        // Conservative default until the next VAD window re-evaluates it --
        // there's no fresh RMS reading yet at the exact instant of resume.
        RecordingActivityStateHolder.set(RecordingActivityState.QUIET)
        writePauseEvent(session, event = "resume", reason = "user")
    }

    private fun writePauseEvent(session: SessionHandle, event: String, reason: String) {
        val app = application as VoiceCaptureApp
        val tMs = audioTimelineElapsedMs()
        lifecycleScope.launch(Dispatchers.IO) {
            app.sessionStore.transcriptFile(session.dir).appendText(PauseEventWriter.encodeLine(tMs, event, reason) + "\n")
        }
    }

    /** Connects the STT client and fans its output into the UI state + `live-transcript.jsonl`. */
    private fun startSttPipeline(stt: StreamingSttClient, session: SessionHandle) {
        val app = application as VoiceCaptureApp
        val statusJob = lifecycleScope.launch {
            stt.connectionState().collect { state ->
                if (state == SttConnectionState.CONNECTED) sttEverConnected = true
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
                // Bead asn-02h.1/asn-02h.2: BLINK is the transcription
                // heartbeat -- fires on every INBOUND (non-final) partial
                // RESPONSE from the STT engine (proves the full
                // mic->socket->engine->response round trip), throttled to at
                // most one per ~2.5s -- never on an outbound audio-chunk
                // send, which would false-reassure when the engine is
                // actually down. NOD is the slower per-turn beat: a final
                // segment landing always fires it, no throttle.
                if (partial.isFinal) {
                    DuckPulseStateHolder.emit(nodPulseForFinalSegment())
                } else if (blinkHeartbeat.onInboundPartial(SystemClock.elapsedRealtime())) {
                    DuckPulseStateHolder.emit(DuckPulse.BLINK)
                }
                // Bead asn-r60: fold in sttTimelineTracker's accumulated
                // offset before this timestamp reaches the UI or disk -- see
                // its KDoc for why a manual-pause resume's brand-new STT
                // connection needs this to keep live-transcript.jsonl
                // monotonic. recordRawEndMs must see the connection-relative
                // (pre-offset) value, so it's captured before adjusting.
                sttTimelineTracker.recordRawEndMs(partial.endMs)
                val startMs = sttTimelineTracker.toSessionMs(partial.startMs)
                val endMs = sttTimelineTracker.toSessionMs(partial.endMs)
                TranscriptStateHolder.update { ui ->
                    val cleared = if (partial.text.isNotBlank()) ui.copy(silenceHintVisible = false) else ui
                    if (partial.isFinal) {
                        cleared.copy(
                            finalLines = cleared.finalLines + TranscriptLine(partial.text, startMs, endMs),
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
                        val line = LiveTranscriptLine(startMs, endMs, partial.text, final = true)
                        app.sessionStore.transcriptFile(session.dir).appendText(LiveTranscriptWriter.encodeLine(line) + "\n")
                    }
                    // Non-blocking hand-off (bead vn-edu.38) -- see tagLineChannel's
                    // KDoc. DROP_OLDEST means a saturated tag pump (e.g. a slow
                    // AnthropicTagScorer call) sheds old lines rather than ever
                    // making this collector wait.
                    //
                    // Bead asn-k7i cause C: this event's atMs is the
                    // recording-elapsed clock (SystemClock.elapsedRealtime() -
                    // startElapsedRealtimeMs), the same one startTicker's Tick
                    // events use below -- NOT partial.endMs, which is
                    // AssemblyAI's own socket-relative word timestamp (a
                    // different clock, with a different zero point: it starts
                    // counting from when the STT WebSocket connects, not when
                    // this recording did). Feeding TagCoordinator's single
                    // cadence gate two different clocks depending on which
                    // event triggered it is exactly what let final lines rarely
                    // satisfy the gate, or stamp it with a value already behind
                    // where the ticker's own clock was.
                    //
                    // Bead asn-r60: deliberately NOT `endMs` (the
                    // sttTimelineTracker-adjusted value a few lines up) --
                    // these are two independent clock domains that happen to
                    // both derive from `partial`. `endMs` exists only to keep
                    // the transcript pane / live-transcript.jsonl segment
                    // lines monotonic across a pause-triggered STT reconnect;
                    // the tag pump's cadence gate wants ONE shared
                    // recording-elapsed clock across every event (Tick and
                    // FinalLine alike), which raw wall-clock time already is
                    // -- monotonic (in fact strictly increasing) regardless of
                    // any pause, since it never freezes and is never
                    // STT-connection-relative. Do not "simplify" these back
                    // into one variable.
                    val recordingElapsedMs = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
                    tagLineChannel?.trySend(TagPumpEvent.FinalLine(partial.text, recordingElapsedMs))
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
        // Bead vn-edu.47: TagCoordinator resolves this lazily (at most once
        // per session, on its first actual scorer call) rather than this
        // method blocking session start on a network round-trip -- see
        // TagCoordinator's treeProvider KDoc.
        val coordinator = TagCoordinator(app.newTagScorer(), treeProvider = { app.currentTagTree() })
        tagCoordinator = coordinator
        // Bead asn-k7i cause D: fire the tree fetch now instead of waiting for
        // the first real score call to discover it isn't resolved yet -- a
        // slow GitHub fetch (10s connect/15s read) was otherwise adding up to
        // 25s to the first tag of the day. prewarmTree is fire-and-forget
        // (not suspend) so it can't delay this method itself, and the first
        // real score call joins this exact same in-flight fetch rather than
        // starting a second one -- see TagCoordinator.prewarmTree/resolveTree.
        coordinator.prewarmTree(lifecycleScope)
        // Bead asn-45m: fresh per session, same lifetime as coordinator --
        // see tagChipRail's own KDoc.
        val rail = TagChipRail()
        tagChipRail = rail
        // Bead asn-45m: resolves the same tree TagCoordinator's treeProvider
        // does (TagTreeRepository's own cache makes calling this cheap even
        // right after prewarmTree's own fetch above) and publishes it for
        // RecordingScreen's filing-destination ribbon + tag picker -- see
        // TagTreeStateHolder's KDoc for why the UI never calls
        // app.currentTagTree() itself.
        lifecycleScope.launch(Dispatchers.IO) { TagTreeStateHolder.update(app.currentTagTree()) }
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
                // Feeds the rail's suggested side -- sticky-removed tags stay
                // excluded from TagChipRail.chips() even though this always
                // hands the tracker's raw output through unfiltered. Done
                // before encodeLine below so a proposal the user already
                // approved (bead asn-0jk) is reflected in this exact
                // snapshot line, not one tick later.
                rail.onSuggested(changed)
                val approvedKeys = rail.approvedKeys()
                app.sessionStore.transcriptFile(session.dir)
                    .appendText(TagsEventWriter.encodeLine(event.atMs, changed, approvedKeys) + "\n")
                TagRailStateHolder.update(rail.chips())
            }
        }
    }

    /**
     * Starts the live-summary pipeline (bead asn-evl): a dedicated pump
     * coroutine drains [summaryTickChannel] serially -- [SummaryCoordinator]
     * is not thread-safe, same reasoning as [TagCoordinator] -- and, whenever
     * a round actually changes anything, publishes the new [SummaryUiState]
     * to [SummaryStateHolder], appends a `summary` event line to
     * `live-transcript.jsonl`, and rewrites `summary.md` in full (bead
     * asn-evl: cheap at this bullet-list size, and keeps the uploaded file
     * always exactly matching the coordinator's own state).
     *
     * [app.newSummaryGenerator] is null when no Anthropic key is configured
     * -- [SummaryCoordinator.onTick] then unconditionally no-ops on every
     * tick, so this pump still runs (harmlessly) rather than needing its own
     * keyless branch here.
     */
    private fun startSummaryPipeline(app: VoiceCaptureApp, session: SessionHandle) {
        val coordinator = SummaryCoordinator(app.newSummaryGenerator())
        summaryCoordinator = coordinator
        val channel = Channel<Long>(capacity = SUMMARY_TICK_CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        summaryTickChannel = channel
        summaryPumpJob = lifecycleScope.launch(Dispatchers.IO) {
            for (atMs in channel) {
                val fullTranscript = TranscriptStateHolder.state.value.finalLines.joinToString(" ") { it.text }
                val result = runCatching { coordinator.onTick(atMs, fullTranscript) }.getOrNull() ?: continue
                SummaryStateHolder.update(
                    SummaryUiState(bullets = result.bullets, newestIndex = result.newestIndex, updatedAtMs = atMs, stale = result.stale),
                )
                // Bead asn-02h.3: a summary round completing is the real
                // "summary round" beat (SummaryCoordinator.onTick returning
                // non-null -- its internal due/growth/word-count gating
                // means the caller can't know in advance whether a given
                // tick will actually attempt one, only that it just did).
                // Whether this WRITE pulse actually plays (vs. being a
                // no-op against the held WRITE base state while the notes
                // card is up) is RecordingScreen's call -- see
                // shouldPlayPulse's own KDoc.
                DuckPulseStateHolder.emit(DuckPulse.WRITE)
                app.sessionStore.transcriptFile(session.dir).appendText(SummaryEventWriter.encodeLine(atMs, result.added) + "\n")
                app.sessionStore.summaryFile(session.dir).writeText(SummaryMarkdownWriter.render(result.bullets))
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
     *
     * Bead asn-l34: the per-tick work runs through [runResilientTicker]
     * rather than directly in a bare `while` loop -- see its KDoc for why.
     * In short, a single tick throwing (observed on ae8ece8 as the elapsed
     * counter freezing mid-recording and never advancing again) must never
     * be able to silently kill this loop for the rest of the session.
     *
     * Bead asn-o63: the big timer / notification text is [audioTimelineClock]'s
     * reading -- real recorded *content* time (paused across both hard and
     * soft pauses, matching `recorded_ms`), NOT the raw wall-clock `elapsed`
     * below. Live-test feedback on the original asn-r60 design (a separate
     * `visibleClock` that only froze for a hard pause) was that 10 minutes of
     * wall-clock with 2 minutes of actual content needs to read "2:00", not
     * a number that keeps ticking through auto-paused spans. [TagPumpEvent.Tick]
     * deliberately keeps using raw `elapsed` unchanged -- that timestamp is
     * bead asn-k7i's concurrently-owned tag-pump timing surface, out of
     * scope here.
     */
    private fun startTicker() {
        lifecycleScope.launch {
            runResilientTicker(
                scope = this,
                intervalMs = TICK_INTERVAL_MS,
                continueCondition = { currentSession != null },
                onTickFailure = { e -> Log.e(TAG, "ticker tick failed; elapsed clock keeps running", e) },
            ) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - startElapsedRealtimeMs
                val contentElapsed = audioTimelineClock.elapsedMs(now, elapsed)
                RecordingStateHolder.update { it.copy(elapsedMs = contentElapsed) }
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(contentElapsed))
                tagLineChannel?.trySend(TagPumpEvent.Tick(TranscriptStateHolder.state.value.currentPartial, elapsed))
                summaryTickChannel?.trySend(elapsed)
            }
        }
    }

    private fun endRecording() {
        val session = currentSession ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - startElapsedRealtimeMs
        // Bead asn-r60: audioTimelineClock's reading at this instant is
        // exactly "how much audio was actually persisted" -- see its KDoc --
        // which doubles as meta.json's new recorded_ms field with no separate
        // accumulator needed.
        val recordedMs = audioTimelineClock.elapsedMs(now, elapsedMs)
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
        tagChipRail = null

        // Bead asn-evl: same close-not-cancel reasoning as tagLineChannel
        // above -- let the summary pump finish writing out whatever round
        // it's already mid-processing before the for-loop over the channel
        // ends on its own.
        summaryTickChannel?.close()
        summaryTickChannel = null
        val summaryJobToJoin = summaryPumpJob
        summaryPumpJob = null
        summaryCoordinator = null

        // Bead vn-edu.56: snapshot the too-short inputs now, before anything
        // async runs -- both are already fully known at the instant Stop was
        // tapped (finalLines is whatever STT had finalized so far;
        // sttEverConnected only ever flips true->never back to false mid-session).
        val sttWasConnected = sttEverConnected
        val finalWordCount = TranscriptWordCount.count(TranscriptStateHolder.state.value.finalLines)
        val shouldWarn = TooShortPolicy.shouldDiscard(elapsedMs, sttWasConnected, finalWordCount)
        // A non-warned session's signal is pre-completed so TooShortDecision.resolve's
        // early `!shouldWarn` return path never touches TooShortWarningStateHolder at all.
        val saveAnywaySignal = if (shouldWarn) {
            TooShortWarningStateHolder.beginWarning(session.sessionId)
        } else {
            CompletableDeferred(true)
        }

        lifecycleScope.launch {
            // Close the STT socket in parallel with the (independent) audio
            // finalize -- neither should wait on the other.
            val sttCloseJob = stt?.let { launch { runCatching { it.close() } } }
            // Bounded wait: a hung scorer call must never delay the service
            // from stopping -- see TagCoordinator/AnthropicTagScorer's own
            // "never blocks" contract; this is just defense in depth.
            val tagPumpCloseJob = tagJobToJoin?.let { launch { withTimeoutOrNull(TAG_PUMP_SHUTDOWN_TIMEOUT_MS) { it.join() } } }
            val summaryPumpCloseJob = summaryJobToJoin?.let { launch { withTimeoutOrNull(SUMMARY_PUMP_SHUTDOWN_TIMEOUT_MS) { it.join() } } }

            // Bead vn-edu.56: "Session too short to save" -- awaits up to
            // TooShortPolicy.WARNING_WINDOW_MS for a "Save anyway" tap (routed
            // in from the UI via TooShortWarningStateHolder.saveAnyway());
            // times out to "discard" by default. No-op wait when !shouldWarn.
            // finalize/discard below are mutually exclusive -- see TooShortOutcome's KDoc.
            TooShortOutcome.resolveAndRoute(
                shouldWarn = shouldWarn,
                saveAnywaySignal = saveAnywaySignal,
                warningWindowMs = TooShortPolicy.WARNING_WINDOW_MS,
                onWarningResolved = { TooShortWarningStateHolder.clearWarning() },
                finalize = {
                    val finalizeSucceeded = runFinalizeWithWatchdog(app, session, elapsedMs, recordedMs)
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
                },
                discard = {
                    // Discard by default (bead vn-edu.56): delete the whole session
                    // directory -- audio.wal and any live-transcript.jsonl event
                    // lines already written -- so nothing uploads and no row ever
                    // appears (writeMeta/UploadWorker.enqueue are never reached).
                    TooShortSessionDiscarder.discard(session.dir)
                },
            )

            sttCloseJob?.join()
            tagPumpCloseJob?.join()
            summaryPumpCloseJob?.join()
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
    private suspend fun runFinalizeWithWatchdog(app: VoiceCaptureApp, session: SessionHandle, elapsedMs: Long, recordedMs: Long): Boolean {
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
                    // Bead asn-r60: only worth recording when this session
                    // actually trimmed something -- an untouched session
                    // (no pause ever happened) leaves this null, same as a
                    // pre-asn-r60 build would, rather than writing a
                    // recorded_ms that's identical to duration_ms on every
                    // single session going forward.
                    recordedMs = recordedMs.takeIf { it != elapsedMs },
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
