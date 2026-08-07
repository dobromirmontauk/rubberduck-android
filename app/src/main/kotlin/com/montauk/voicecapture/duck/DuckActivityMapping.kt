package com.montauk.voicecapture.duck

import com.montauk.voicecapture.service.RecordingActivityState

/**
 * Bug fix, bead asn-3h6 (device test 2026-08-06): asn-5w3's original mapping
 * collapsed [RecordingActivityState.QUIET] straight to [DuckState.SLEEP]
 * unconditionally -- since a fresh session starts in `QUIET` (see
 * [com.montauk.voicecapture.service.RecordingActivityStateHolder.reset]),
 * that meant a brand-new session rendered the duck fully asleep before any
 * speech ever happened, and the sighted-to-sleep flip on any real pause in
 * talking was near-instantaneous instead of gradual.
 *
 * Fixed mapping -- SPEAKING -> [DuckState.ATTENTIVE] always; QUIET ->
 * [DuckState.ATTENTIVE] while [autoPauseFillFraction] is still `0f` (the
 * leading, invisible span of continuous quiet -- covers a fresh session,
 * where `quietDurationMs`/`autoPauseFillFraction` both start at their
 * defaults of `0`/`0f`), or [DuckState.DROWSY] once it's ramping above `0f`
 * (the trailing auto-pause fill window -- heavy-lidded, still recording,
 * still resumes instantly on speech); AUTO_PAUSED/USER_PAUSED ->
 * [DuckState.SLEEP] (auto and manual pause stay visually identical, per
 * asn-5w3). [autoPauseFillFraction] is deliberately
 * [com.montauk.voicecapture.service.TranscriptUiState.autoPauseFillFraction]
 * itself -- the exact signal [com.montauk.voicecapture.audio.autoPauseFillFraction]
 * already derives from [com.montauk.voicecapture.audio.VoiceActivityDetector.continuousQuietMs]
 * against the user's configured auto-pause threshold -- rather than a
 * second, parallel "5 seconds of quiet" timer that could drift out of sync
 * with the real auto-pause countdown or the settings-tunable threshold
 * (10s/15s/30s/60s) it's measured against.
 *
 * [RecordingActivityState] is asn-r60's real, VAD-driven `StateFlow` on
 * [com.montauk.voicecapture.service.RecordingActivityStateHolder].
 */
fun RecordingActivityState.toDuckState(autoPauseFillFraction: Float = 0f): DuckState = when (this) {
    RecordingActivityState.SPEAKING -> DuckState.ATTENTIVE
    RecordingActivityState.QUIET -> if (autoPauseFillFraction > 0f) DuckState.DROWSY else DuckState.ATTENTIVE
    RecordingActivityState.AUTO_PAUSED, RecordingActivityState.USER_PAUSED -> DuckState.SLEEP
}
