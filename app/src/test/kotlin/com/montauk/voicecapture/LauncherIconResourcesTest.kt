package com.montauk.voicecapture

import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Asset-presence check for the LISTEN-pose adaptive launcher icon (bead
 * vn-edu.63): every layer referenced from `mipmap-anydpi-v26/ic_launcher*.xml`
 * must resolve to a non-zero compile-time resource id, catching a drawable
 * removed/renamed without updating the adaptive-icon XML before it ships --
 * same JVM-only technique as [com.montauk.voicecapture.duck.DuckFrameResourcesTest],
 * no Robolectric/instrumentation needed since resource ids are plain `Int`
 * constants baked in at compile time.
 */
class LauncherIconResourcesTest {

    @Test
    fun `adaptive icon foreground drawable exists`() {
        assertNotEquals(0, R.drawable.ic_launcher_foreground)
    }

    @Test
    fun `adaptive icon monochrome drawable exists`() {
        assertNotEquals(0, R.drawable.ic_launcher_monochrome)
    }

    @Test
    fun `adaptive icon background color exists`() {
        assertNotEquals(0, R.color.ic_launcher_background)
    }

    @Test
    fun `legacy and round launcher mipmaps still exist`() {
        assertNotEquals(0, R.mipmap.ic_launcher)
        assertNotEquals(0, R.mipmap.ic_launcher_round)
    }
}
