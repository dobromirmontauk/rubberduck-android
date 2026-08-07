package com.montauk.voicecapture.ui

import com.montauk.voicecapture.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Asset-presence check for bead vn-edu.73's duck-themed bottom-nav glyphs
 * ([BottomNavBar]): each drawable [BottomNavBar] references must resolve to
 * a non-zero, distinct compile-time resource id, same JVM-only technique as
 * [com.montauk.voicecapture.LauncherIconResourcesTest] and
 * [ModeSwitcherPoseResourcesTest] -- catches an icon deleted/renamed without
 * updating [BottomNavBar]'s `painterResource` calls before it ships.
 */
class NavIconResourcesTest {

    @Test
    fun `all five nav-bar duck glyphs exist and are distinct`() {
        val ids = listOf(
            R.drawable.ic_nav_new_session,
            R.drawable.ic_nav_sessions,
            R.drawable.ic_nav_settings,
            R.drawable.ic_nav_signin,
            R.drawable.ic_nav_signout,
        )
        ids.forEach { assertNotEquals(0, it) }
        assertEquals("all five should be distinct drawables", ids.size, ids.toSet().size)
    }
}
