package com.montauk.voicecapture.testutil

import com.montauk.voicecapture.session.LiveTranscriptLine
import com.montauk.voicecapture.session.LiveTranscriptWriter
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.session.SessionHandle
import com.montauk.voicecapture.session.SessionModeEntry
import com.montauk.voicecapture.session.SessionStore
import com.montauk.voicecapture.session.UploadState
import java.util.Date

/**
 * Deterministic session fixtures shared by the Robolectric interaction tests
 * (vn-edu.35, [com.montauk.voicecapture.ui.AppNavHostInteractionTest]) and the
 * Roborazzi screenshot goldens (vn-edu.34) -- fixed session id, fixed
 * `started_at`, fixed transcript text, so both a semantics-tree assertion and
 * a pixel-diffed golden stay stable across runs and across machines/timezones.
 *
 * Writes directly through [SessionStore]'s own file-writing methods (not
 * hand-rolled file I/O) so a fixture is exactly what a real recording would
 * have produced, and stays in sync if [SessionStore]'s on-disk format changes.
 */
object SessionFixtures {

    /** Always the same instant (2026-08-01T16:00:00Z) -- no `Date()`/`System.currentTimeMillis()` in any fixture. */
    val FIXED_STARTED_AT: Date = Date(1_785_686_400_000L)

    /**
     * Seeds a fully finalized session (meta.json + one final transcript line
     * + upload-state marker) directly under [sessionStore]'s base dir.
     * [transcriptText] drives the session-list row's derived title (see
     * [com.montauk.voicecapture.session.DerivedTitle]) -- keep it at or under
     * 5 words with no leading filler word so the displayed title is exactly
     * [transcriptText] verbatim, not a truncated/filler-stripped version.
     *
     * [title], if given, is written to meta.json's `title` field and takes
     * precedence over the derived title (bead vn-edu.42) -- leave it null
     * (the default) to exercise the derived-title path, the common case for
     * these fixtures.
     */
    fun seedSession(
        sessionStore: SessionStore,
        sessionId: String,
        transcriptText: String,
        durationMs: Long = 125_000L,
        uploadState: UploadState = UploadState.LOCAL,
        startedAt: Date = FIXED_STARTED_AT,
        title: String? = null,
    ) {
        val dir = sessionStore.sessionDir(sessionId)
        dir.mkdirs()
        val handle = SessionHandle(sessionId, dir, startedAt)
        sessionStore.writeMeta(
            handle = handle,
            durationMs = durationMs,
            deviceModel = "Pixel 7",
            appVersion = "0.1.0-test",
            modes = listOf(SessionModeEntry(0L, RecordingMode.DEFAULT.wireValue)),
            title = title,
        )
        sessionStore.transcriptFile(dir).writeText(
            LiveTranscriptWriter.encodeLine(LiveTranscriptLine(t0Ms = 0L, t1Ms = 2_000L, text = transcriptText, final = true)) + "\n",
        )
        sessionStore.setUploadState(dir, uploadState)
    }
}
