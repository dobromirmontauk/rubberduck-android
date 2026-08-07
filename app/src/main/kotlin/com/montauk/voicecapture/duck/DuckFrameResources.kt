package com.montauk.voicecapture.duck

import com.montauk.voicecapture.R

/**
 * Maps each pure [DuckFrame] token to its bundled drawable -- the only place
 * an Android resource id leaks into duck logic. Frames are bundled at 512px
 * (downscaled from 1024px, alpha-bbox-cropped/height-normalized/bottom-
 * anchored -- see `design/clay-poses/` and its normalization script) under
 * `res/drawable-nodpi/` so they render at a fixed pixel size regardless of
 * screen density.
 */
internal fun DuckFrame.drawableRes(): Int = when (this) {
    DuckFrame.ATTENTIVE -> R.drawable.duck_attentive
    DuckFrame.DROWSY -> R.drawable.duck_drowsy
    DuckFrame.SLEEP -> R.drawable.duck_sleep
    DuckFrame.THINK -> R.drawable.duck_think
    DuckFrame.BLINK -> R.drawable.duck_blink
    DuckFrame.NOD -> R.drawable.duck_nod
    DuckFrame.RAISE_HAND -> R.drawable.duck_raise_hand
    DuckFrame.WRITE -> R.drawable.duck_write
    DuckFrame.CELEBRATE -> R.drawable.duck_celebrate
}
