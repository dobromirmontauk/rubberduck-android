package com.montauk.voicecapture.duck

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the system's "Remove animations" accessibility setting is on
 * (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`) -- the thought cloud's
 * continuous drift/shimmer/rising-dots animations all check this and fall
 * back to a static render instead (bead asn-3sm, design board: "Respect
 * reduced-motion"). Read once per composition, not observed live -- this
 * setting changing while the recording screen happens to be open is an
 * acceptable edge case to miss until the next screen visit.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scale == 0f
    }
}
