package com.montauk.voicecapture.duck

import com.montauk.voicecapture.service.RecordingActivity

/** SPEAKING -> LISTENING anim; QUIET -> SLEEPY; either paused kind -> GONE_BRB (bead asn-3sm). */
fun RecordingActivity.toDuckState(): DuckState = when (this) {
    RecordingActivity.SPEAKING -> DuckState.LISTENING
    RecordingActivity.QUIET -> DuckState.SLEEPY
    RecordingActivity.AUTO_PAUSED, RecordingActivity.USER_PAUSED -> DuckState.GONE_BRB
}
