package com.montauk.voicecapture.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class UploadState { LOCAL, QUEUED, UPLOADED }

data class SessionHandle(val sessionId: String, val dir: File, val startedAt: Date)

data class SessionSummary(
    val sessionId: String,
    val durationMs: Long,
    val uploadState: UploadState,
)

/**
 * Owns the on-disk layout of `files/sessions/<session-id>/` and the small
 * bit of bookkeeping (meta.json, upload-state marker) layered on top of the
 * audio artifacts that [com.montauk.voicecapture.audio.AudioEngine] writes.
 *
 * Takes a plain [File] base directory rather than an Android `Context` so the
 * finalize/recovery logic is coverable by ordinary JVM unit tests; the one
 * call site that needs `Context.filesDir` is [com.montauk.voicecapture.VoiceCaptureApp].
 */
class SessionStore(private val baseDir: File) {

    companion object {
        const val AUDIO_WAL_FILENAME = "audio.wal"
        const val AUDIO_OGG_FILENAME = "audio.ogg"
        const val META_FILENAME = "meta.json"
        const val TRANSCRIPT_FILENAME = "live-transcript.jsonl"
        private const val UPLOAD_STATE_FILENAME = ".upload-state"

        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val json = Json { prettyPrint = true }

        fun isoTimestamp(date: Date): String = ISO_FORMAT.format(date)
    }

    init {
        baseDir.mkdirs()
    }

    fun sessionDir(sessionId: String): File = File(baseDir, sessionId)

    fun walFile(dir: File): File = File(dir, AUDIO_WAL_FILENAME)
    fun oggFile(dir: File): File = File(dir, AUDIO_OGG_FILENAME)
    fun metaFile(dir: File): File = File(dir, META_FILENAME)
    fun transcriptFile(dir: File): File = File(dir, TRANSCRIPT_FILENAME)
    private fun uploadStateFile(dir: File): File = File(dir, UPLOAD_STATE_FILENAME)

    /** Creates a fresh session directory and returns its id/handle. Does not touch audio files. */
    fun createSession(now: Date = Date()): SessionHandle {
        val sessionId = SessionId.generate(now)
        val dir = sessionDir(sessionId)
        require(dir.mkdirs()) { "Session directory already exists or could not be created: $dir" }
        return SessionHandle(sessionId, dir, now)
    }

    /**
     * Writes meta.json and an empty live-transcript.jsonl for a session whose
     * audio has already been finalized to [AUDIO_OGG_FILENAME] by the caller
     * (normally `AudioEngine.finalizeToOgg`, called from [RecordingService]
     * on stop, or from [recoverUnfinalizedSessions] after a crash).
     */
    fun writeMeta(
        handle: SessionHandle,
        durationMs: Long,
        deviceModel: String,
        appVersion: String,
    ) {
        val meta = SessionMeta(
            sessionId = handle.sessionId,
            startedAt = isoTimestamp(handle.startedAt),
            durationMs = durationMs,
            device = deviceModel,
            appVersion = appVersion,
            stt = null,
            schemaVersion = 1,
        )
        metaFile(handle.dir).writeText(json.encodeToString(meta))

        val transcript = transcriptFile(handle.dir)
        if (!transcript.exists()) transcript.createNewFile()

        if (!uploadStateFile(handle.dir).exists()) {
            setUploadState(handle.dir, UploadState.LOCAL)
        }
    }

    fun setUploadState(dir: File, state: UploadState) {
        uploadStateFile(dir).writeText(state.name)
    }

    fun readUploadState(dir: File): UploadState {
        val file = uploadStateFile(dir)
        if (!file.exists()) return UploadState.LOCAL
        return runCatching { UploadState.valueOf(file.readText().trim()) }.getOrDefault(UploadState.LOCAL)
    }

    /**
     * Session directories where [AUDIO_WAL_FILENAME] exists but
     * [AUDIO_OGG_FILENAME] doesn't: the WAL was written but the process died
     * before the clean-stop remux ran. Returns them so the caller (app start)
     * can re-run `AudioEngine.finalizeToOgg` against each and then call
     * [writeMeta] to complete finalization -- see docs/audio-wal.md.
     */
    fun findUnfinalizedSessions(): List<SessionHandle> {
        val dirs = baseDir.listFiles { file -> file.isDirectory } ?: emptyArray()
        return dirs
            .filter { dir -> walFile(dir).exists() && !oggFile(dir).exists() }
            .mapNotNull { dir -> handleFromDir(dir) }
    }

    private fun handleFromDir(dir: File): SessionHandle? {
        if (!SessionId.isValid(dir.name)) return null
        val startedAt = runCatching { parseStartedAtFromSessionId(dir.name) }.getOrNull() ?: Date(dir.lastModified())
        return SessionHandle(dir.name, dir, startedAt)
    }

    private fun parseStartedAtFromSessionId(sessionId: String): Date {
        val timestampPart = sessionId.substringBeforeLast('_')
        val format = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).apply { timeZone = TimeZone.getDefault() }
        return format.parse(timestampPart) ?: Date()
    }

    /** Lists all sessions that have at least a meta.json, newest first. */
    fun listSessions(): List<SessionSummary> {
        val dirs = baseDir.listFiles { file -> file.isDirectory } ?: emptyArray()
        return dirs
            .filter { dir -> metaFile(dir).exists() }
            .mapNotNull { dir ->
                runCatching {
                    val meta = json.decodeFromString(SessionMeta.serializer(), metaFile(dir).readText())
                    // startedAt (not sessionId) drives ordering: the session-id's
                    // minute-resolution timestamp + random suffix don't sort
                    // correctly when two sessions start in the same minute.
                    meta.startedAt to SessionSummary(meta.sessionId, meta.durationMs, readUploadState(dir))
                }.getOrNull()
            }
            .sortedByDescending { (startedAt, _) -> startedAt }
            .map { (_, summary) -> summary }
    }
}
