package com.montauk.voicecapture.duck

import com.montauk.voicecapture.service.RecordingActivityState

/**
 * SPEAKING -> ATTENTIVE; QUIET or either paused kind -> SLEEP (bead asn-3sm
 * v3: quiet no longer gets its own drowsy tier, and auto-/manual-pause are
 * now visually identical too -- all three collapse onto the same
 * [DuckState.SLEEP] pose). [RecordingActivityState] is asn-r60's real,
 * VAD-driven `StateFlow` on [com.montauk.voicecapture.service.RecordingActivityStateHolder].
 */
fun RecordingActivityState.toDuckState(): DuckState = when (this) {
    RecordingActivityState.SPEAKING -> DuckState.ATTENTIVE
    RecordingActivityState.QUIET, RecordingActivityState.AUTO_PAUSED, RecordingActivityState.USER_PAUSED -> DuckState.SLEEP
}
