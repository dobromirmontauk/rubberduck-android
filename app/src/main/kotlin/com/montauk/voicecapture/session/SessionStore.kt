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
    val title: String,
    val startedAt: Date,
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

        // encodeDefaults=true: the ingest contract lists `stt` and `schema_version`
        // as required meta.json keys (stt may be null, but the key must be
        // present) -- without this, kotlinx.serialization silently omits any
        // field left at its Kotlin default, which is exactly `stt` and
        // `schema_version` on every session recorded without live STT wired up.
        private val json = Json { prettyPrint = true; encodeDefaults = true }

        fun isoTimestamp(date: Date): String = ISO_FORMAT.format(date)
        fun parseIsoTimestamp(iso: String): Date? = runCatching { ISO_FORMAT.parse(iso) }.getOrNull()
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
        modes: List<SessionModeEntry> = listOf(SessionModeEntry(0L, RecordingMode.DEFAULT.wireValue)),
        title: String? = null,
    ) {
        val meta = SessionMeta(
            sessionId = handle.sessionId,
            startedAt = isoTimestamp(handle.startedAt),
            durationMs = durationMs,
            device = deviceModel,
            appVersion = appVersion,
            stt = null,
            modes = modes,
            title = title,
            schemaVersion = 1,
        )
        metaFile(handle.dir).writeText(json.encodeToString(meta))

        val transcript = transcriptFile(handle.dir)
        if (!transcript.exists()) transcript.createNewFile()

        if (!uploadStateFile(handle.dir).exists()) {
            setUploadState(handle.dir, UploadState.LOCAL)
        }
    }

    /**
     * Patches meta.json's `title` field for [sessionId] without touching any
     * other field -- lands the LLM-generated title (bead vn-edu.42) well
     * after [writeMeta]'s own initial write, from either
     * [com.montauk.voicecapture.service.RecordingService]'s post-finalize
     * async task or the detail-view backfill path. No-op if meta.json
     * doesn't exist or fails to parse (session vanished, or predates a
     * schema this build can read -- never worth crashing over).
     */
    fun updateTitle(sessionId: String, title: String) {
        val file = metaFile(sessionDir(sessionId))
        if (!file.exists()) return
        val meta = runCatching { json.decodeFromString(SessionMeta.serializer(), file.readText()) }.getOrNull() ?: return
        file.writeText(json.encodeToString(meta.copy(title = title)))
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
                    val startedAtDate = parseIsoTimestamp(meta.startedAt) ?: Date(dir.lastModified())
                    val title = resolveTitle(meta)
                    // meta.startedAt (the raw ISO string, not sessionId) drives
                    // ordering: the session-id's minute-resolution timestamp +
                    // random suffix don't sort correctly when two sessions
                    // start in the same minute, but ISO-8601 UTC strings do.
                    meta.startedAt to SessionSummary(meta.sessionId, title, startedAtDate, meta.durationMs, readUploadState(dir))
                }.getOrNull()
            }
            .sortedByDescending { (startedAt, _) -> startedAt }
            .map { (_, summary) -> summary }
    }

    /**
     * Title-preference chain shared by the session list and (via
     * [readMeta] + [readTranscriptLines]) the detail screen's header (bead
     * vn-edu.42): the LLM-generated `meta.title` when present and non-blank,
     * else [DerivedTitle]'s words from the first final transcript line,
     * else [DerivedTitle.UNTITLED].
     */
    private fun resolveTitle(meta: SessionMeta): String =
        meta.title?.takeIf { it.isNotBlank() } ?: DerivedTitle.from(readTranscriptLines(meta.sessionId).firstOrNull()?.text)

    /**
     * Ids of every session currently marked LOCAL -- finalized while signed
     * out (bead vn-edu.29: [com.montauk.voicecapture.service.RecordingService]
     * never queues an upload with no GitHub token configured), so nothing
     * ever attempted to leave the device. [com.montauk.voicecapture.upload.UploadWorker.enqueueBacklog]
     * calls this right after a sign-in to drain the backlog.
     */
    fun localSessionIds(): List<String> =
        listSessions().filter { it.uploadState == UploadState.LOCAL }.map { it.sessionId }

    /** Reads meta.json for [sessionId], or null if the session or its meta.json doesn't exist. */
    fun readMeta(sessionId: String): SessionMeta? {
        val file = metaFile(sessionDir(sessionId))
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(SessionMeta.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Deletes [sessionId]'s entire on-disk directory (bead vn-edu.55) --
     * backing the phone-local "delete from phone" and "archive all
     * integrated sessions" actions. Mirrors
     * [com.montauk.voicecapture.service.TooShortSessionDiscarder]'s best-
     * effort recursive delete + exists-check verification; unlike that
     * discard path (a session that never finished recording), this runs
     * against a fully finalized session directory reached from the
     * Sessions/detail screens. Callers ([DeleteConfirmPolicy],
     * [BulkArchiveEligibility]) own eligibility and confirmation copy -- this
     * is only the file removal, and it never touches the vault/uploader:
     * nothing in this function's signature or body can reach either.
     * Returns true if [sessionId]'s directory doesn't exist afterward
     * (including if it never existed).
     */
    fun deleteSession(sessionId: String): Boolean {
        val dir = sessionDir(sessionId)
        if (!dir.exists()) return true
        runCatching { dir.deleteRecursively() }
        return !dir.exists()
    }

    /** Reads and parses every line of live-transcript.jsonl for [sessionId], skipping any that fail to parse. */
    fun readTranscriptLines(sessionId: String): List<LiveTranscriptLine> {
        val file = transcriptFile(sessionDir(sessionId))
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString(LiveTranscriptLine.serializer(), line) }.getOrNull() }
    }
}
