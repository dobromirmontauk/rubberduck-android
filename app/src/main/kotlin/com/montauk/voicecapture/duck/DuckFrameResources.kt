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
    DuckFrame.THINKING_1 -> R.drawable.duck_thinking_01
    DuckFrame.THINKING_2 -> R.drawable.duck_thinking_02
    DuckFrame.THINKING_3 -> R.drawable.duck_thinking_03
    DuckFrame.HAPPY_BOUNCE_1 -> R.drawable.duck_happy_01
    DuckFrame.HAPPY_BOUNCE_2 -> R.drawable.duck_happy_02
    DuckFrame.HAPPY_BOUNCE_3 -> R.drawable.duck_happy_03
}
