package com.montauk.voicecapture.logging

import android.util.Log
import java.io.File

/**
 * App-wide state-transition/event logging facility (bead asn-jht, user
 * directive 2026-08-07: "we must ALWAYS be able to pull logs and see what
 * the app is doing"). One consistent tag ("rubberduck") across every
 * component, always at INFO, always in [LogFormatter]'s grep-able
 * `key=value` shape, and always mirrored to a rolling on-device file
 * ([RollingFileSink]) so logs survive logcat's ring buffer rotating away old
 * lines -- pull the file with:
 *
 * ```
 * adb pull /data/data/com.montauk.voicecapture/files/rubberduck.log
 * ```
 *
 * (see README's dev section for the full pull command, including the rolled
 * `.1` file).
 *
 * Contract every caller MUST honor: never pass transcript text or raw audio
 * through [i] -- only state names, counts, ids, and other non-content
 * metadata. Tag NAMES are an explicit, deliberate exception (bead asn-jht's
 * acceptance criteria allow them -- they're short, bounded, and not
 * free-form user speech). Never call this from a per-audio-frame hot path;
 * aggregate into a counter and log the counter instead (see e.g.
 * [com.montauk.voicecapture.stt.AssemblyAiStreamingSttClient]'s
 * partial/final counters).
 */
object RubberduckLog {
    private const val TAG = "rubberduck"

    @Volatile private var sink: LogSink? = null

    /** Call once, from [com.montauk.voicecapture.VoiceCaptureApp.onCreate]. */
    fun init(filesDir: File) {
        sink = RollingFileSink(File(filesDir, RollingFileSink.LOG_FILE_NAME))
    }

    /** Test-only seam: point the facility at a fake [LogSink] to assert on captured lines, or back at nothing with `null`. Always restore to `null` in an `@After`. */
    fun overrideSinkForTest(testSink: LogSink?) {
        sink = testSink
    }

    /** Logs one INFO event: [component] (e.g. "RecordingActivityState"), [event] (e.g. "transition"), plus any `key to value` fields. */
    fun i(component: String, event: String, vararg fields: Pair<String, Any?>) {
        val line = LogFormatter.format(System.currentTimeMillis(), component, event, fields.toList())
        Log.i(TAG, line)
        sink?.append(line)
    }
}
