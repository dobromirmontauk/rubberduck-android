package com.montauk.voicecapture.duck

import com.montauk.voicecapture.service.RecordingActivityState

/**
 * SPEAKING -> LISTENING anim; QUIET -> SLEEPY; either paused kind ->
 * SLEEPING (bead asn-3sm). [RecordingActivityState] is asn-r60's real,
 * VAD-driven `StateFlow` on [com.montauk.voicecapture.service.RecordingActivityStateHolder] --
 * this superseded a crude stand-in derived from transcript signals
 * (`crudeRecordingActivity`/`RecordingActivitySource`, both deleted) once
 * asn-r60 landed on `main`.
 */
fun RecordingActivityState.toDuckState(): DuckState = when (this) {
    RecordingActivityState.SPEAKING -> DuckState.LISTENING
    RecordingActivityState.QUIET -> DuckState.SLEEPY
    RecordingActivityState.AUTO_PAUSED, RecordingActivityState.USER_PAUSED -> DuckState.SLEEPING
}
