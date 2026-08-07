package com.montauk.voicecapture.ui

import com.montauk.voicecapture.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Asset-presence check for bead vn-edu.64's mode-switcher pose images.
 * [RecordingScreen.kt]'s `RecordingMode.poseDrawableRes()` is file-private
 * (not `internal`, unlike [com.montauk.voicecapture.duck.DuckFrame
 * .drawableRes]), so this can't call it directly the way
 * `DuckFrameResourcesTest` calls the duck-engine equivalent -- instead it
 * pins the three resource ids the mapping is known to use, catching the
 * same failure mode (an asset deleted/renamed without updating the mapping)
 * without needing to widen that function's visibility just for a test.
 */
class ModeSwitcherPoseResourcesTest {

    @Test
    fun `all three mode-switcher pose drawables exist and are distinct`() {
        val listen = R.drawable.duck_attentive // reused from the DuckFrame engine, not a duplicate asset
        val converse = R.drawable.duck_mode_converse
        val challenge = R.drawable.duck_mode_challenge

        assertNotEquals(0, listen)
        assertNotEquals(0, converse)
        assertNotEquals(0, challenge)
        assertEquals("all three should be distinct drawables", 3, setOf(listen, converse, challenge).size)
    }
}
