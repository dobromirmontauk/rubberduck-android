package com.montauk.voicecapture.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Standard Material 3 bottom nav, 4 items, per the scope addition: New
 * Session is an action-tab (starts recording + navigates, not a plain tab
 * swap). The 4th tab is also an action rather than a plain tab swap, and
 * adapts to auth state (bead vn-edu.29 -- there's no login gate, so this tab
 * is how a signed-out user finds the connect flow at all): "Sign In" when
 * signed out (navigates to the login screen), "Log Out" when connected
 * (opens a confirm dialog rather than navigating directly). Hidden on the
 * recording and login screens -- see [AppNavHost].
 */
@Composable
fun BottomNavBar(
    currentRoute: String?,
    isConnectedToGithub: Boolean,
    onNewSession: () -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
    onAuthTapped: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.RECORDING,
            onClick = onNewSession,
            icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
            label = { Text("New Session") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SESSIONS,
            onClick = onSessions,
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Sessions") },
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SETTINGS,
            onClick = onSettings,
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
        NavigationBarItem(
            // Always an action (opens a dialog or navigates), never a highlighted
            // destination -- the bar itself is hidden on Routes.LOGIN anyway, same
            // as it always was for the old unconditional "Log Out" action-tab.
            selected = false,
            onClick = onAuthTapped,
            icon = {
                Icon(
                    if (isConnectedToGithub) Icons.AutoMirrored.Filled.Logout else Icons.AutoMirrored.Filled.Login,
                    contentDescription = null,
                )
            },
            label = { Text(if (isConnectedToGithub) "Log Out" else "Sign In") },
        )
    }
}
