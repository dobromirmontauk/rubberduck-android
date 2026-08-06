package com.montauk.voicecapture.service

import kotlinx.coroutines.flow.StateFlow

/**
 * The four-way recording activity signal the duck view binds to (bead
 * asn-3sm): SPEAKING/QUIET drive the duck's LISTENING/SLEEPY animation
 * (see `com.montauk.voicecapture.duck.toDuckState`); either paused state
 * hides the duck behind the BRB card.
 */
enum class RecordingActivity { SPEAKING, QUIET, AUTO_PAUSED, USER_PAUSED }

/**
 * Narrow interface the duck view consumes for its activity signal -- matches
 * the shape of the `StateFlow<RecordingActivity>` agent asn-r60 is landing on
 * `RecordingService`. Once that lands, a real implementation backed by that
 * StateFlow can replace [crudeRecordingActivity]'s call site in
 * `RecordingScreen` with a one-line change; nothing in the duck package
 * needs to change.
 */
interface RecordingActivitySource {
    val activity: StateFlow<RecordingActivity>
}

/**
 * Stub (bead asn-3sm): asn-r60's real pause/speaking StateFlow hasn't landed
 * yet, so this derives a crude approximation from signals that already
 * exist on [TranscriptUiState] -- active partial/final transcript text means
 * SPEAKING, [TranscriptUiState.silenceHintVisible] means QUIET. Neither
 * AUTO_PAUSED nor USER_PAUSED can be produced this way (no pause concept
 * exists yet), so SLEEPING is unreachable from live recording state until
 * the real StateFlow replaces this call.
 */
fun crudeRecordingActivity(transcript: TranscriptUiState): RecordingActivity = when {
    transcript.silenceHintVisible -> RecordingActivity.QUIET
    else -> RecordingActivity.SPEAKING
}
