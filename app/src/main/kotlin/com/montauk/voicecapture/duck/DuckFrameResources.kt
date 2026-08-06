package com.montauk.voicecapture.duck

import com.montauk.voicecapture.R

/**
 * Maps each pure [DuckFrame] token to its bundled drawable -- the only place
 * an Android resource id leaks into duck logic. Frames are bundled at 512px
 * (downscaled from the 1024px clay-pose source library, `design/clay-poses/`)
 * under `res/drawable-nodpi/` so they render at a fixed pixel size regardless
 * of screen density rather than being density-upscaled/downscaled again.
 */
internal fun DuckFrame.drawableRes(): Int = when (this) {
    DuckFrame.IDLE_BREATHING_1 -> R.drawable.duck_idle_01
    DuckFrame.IDLE_BREATHING_2 -> R.drawable.duck_idle_02
    DuckFrame.IDLE_BREATHING_3 -> R.drawable.duck_idle_03
    DuckFrame.IDLE_BREATHING_4 -> R.drawable.duck_idle_04
    DuckFrame.BLINK_1 -> R.drawable.duck_blink_01
    DuckFrame.BLINK_2 -> R.drawable.duck_blink_02
    DuckFrame.BLINK_3 -> R.drawable.duck_blink_03
    DuckFrame.LISTENING_INTRO_1 -> R.drawable.duck_listen_intro_01
    DuckFrame.LISTENING_INTRO_2 -> R.drawable.duck_listen_intro_02
    DuckFrame.LISTENING_INTRO_3 -> R.drawable.duck_listen_intro_03
    DuckFrame.SLEEPY_1 -> R.drawable.duck_sleepy_01
    DuckFrame.SLEEPY_2 -> R.drawable.duck_sleepy_02
    // Bead asn-ek1 landed the real "writing-on-notepad" poses (41-43) this
    // bead's design board flagged as an asset gap -- the DuckFrame.THINKING_*
    // tokens (and DuckAnimationEngine's THINKING phase) are unchanged, only
    // the drawable each maps to swapped from the thinking-pose placeholder.
    DuckFrame.THINKING_1 -> R.drawable.duck_notes_scribble_01
    DuckFrame.THINKING_2 -> R.drawable.duck_notes_scribble_02
    DuckFrame.THINKING_3 -> R.drawable.duck_notes_scribble_03
    DuckFrame.HAPPY_BOUNCE_1 -> R.drawable.duck_happy_01
    DuckFrame.HAPPY_BOUNCE_2 -> R.drawable.duck_happy_02
    DuckFrame.HAPPY_BOUNCE_3 -> R.drawable.duck_happy_03
    DuckFrame.SLEEPING_1 -> R.drawable.duck_sleeping_01
    DuckFrame.SLEEPING_2 -> R.drawable.duck_sleeping_02
    DuckFrame.HEAD_TURN_1 -> R.drawable.duck_head_turn_01
    DuckFrame.HEAD_TURN_2 -> R.drawable.duck_head_turn_02
    DuckFrame.HEAD_TURN_3 -> R.drawable.duck_head_turn_03
    DuckFrame.HEAD_TURN_4 -> R.drawable.duck_head_turn_04
    DuckFrame.HAND_RAISE_1 -> R.drawable.duck_hand_raise_01
    DuckFrame.HAND_RAISE_2 -> R.drawable.duck_hand_raise_02
}
