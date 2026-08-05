package com.montauk.voicecapture.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
 * swap), Log Out is an action too (opens a confirm dialog rather than
 * navigating directly). Hidden on the recording and sign-in screens -- see
 * [AppNavHost].
 */
@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNewSession: () -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
    onLogOut: () -> Unit,
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
            selected = false,
            onClick = onLogOut,
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            label = { Text("Log Out") },
        )
    }
}
