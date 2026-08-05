package com.montauk.voicecapture.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.montauk.voicecapture.BuildConfig

/**
 * Standard Material 3 bottom nav, 4 items, per the scope addition: New
 * Session is an action-tab (starts recording + navigates, not a plain tab
 * swap). The 4th tab is also an action rather than a plain tab swap, and
 * adapts to auth state (bead vn-edu.29 -- there's no login gate, so this tab
 * is how a signed-out user finds the connect flow at all): "Sign In" when
 * signed out (navigates to the login screen), "Log Out" when connected
 * (opens a confirm dialog rather than navigating directly). Hidden on the
 * recording and login screens -- see [AppNavHost].
 *
 * [onNewSessionLongPress] opens the debug fixture picker (bead vn-edu.20) --
 * wired up only in [BuildConfig.DEBUG] builds, via a hand-rolled item
 * ([DebugNewSessionItem]) rather than stacking a long-press gesture on top
 * of the stock [NavigationBarItem]'s own click handling, which risks the two
 * gesture detectors fighting over the same tap.
 */
@Composable
fun BottomNavBar(
    currentRoute: String?,
    isConnectedToGithub: Boolean,
    onNewSession: () -> Unit,
    onNewSessionLongPress: () -> Unit,
    onSessions: () -> Unit,
    onSettings: () -> Unit,
    onAuthTapped: () -> Unit,
) {
    NavigationBar {
        if (BuildConfig.DEBUG) {
            DebugNewSessionItem(
                selected = currentRoute == Routes.RECORDING,
                onClick = onNewSession,
                onLongPress = onNewSessionLongPress,
            )
        } else {
            NavigationBarItem(
                selected = currentRoute == Routes.RECORDING,
                onClick = onNewSession,
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                label = { Text("New Session") },
            )
        }
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

/**
 * Material3's own `NavigationBarItem` pins its height to this value
 * regardless of the incoming constraints (`constrainHeight(NavigationBarHeight
 * .roundToPx())` in `NavigationBarItemLayout`, material3 1.3.1), so that the
 * bar comes out 80dp tall no matter how much vertical space its container
 * happens to offer -- `Scaffold`'s bottomBar slot is measured with
 * `looseConstraints` (min zeroed, `maxHeight` = the full Scaffold height; see
 * `Scaffold.kt`), so "how much space it happens to offer" is effectively the
 * whole screen. [DebugNewSessionItem] has to pin the same way explicitly
 * (`NavigationBarTokens.ContainerHeight` isn't public API) -- it previously
 * used `fillMaxHeight()` instead, which measured against that same
 * near-full-screen max, inflating this one item (and therefore the whole
 * Row, and therefore the bar Scaffold reserved space for) to near-full-screen
 * height and collapsing the sessions list above it (vn-edu.33).
 */
private val NavigationBarItemHeight = 80.dp

/**
 * Debug-only stand-in for the "New Session" [NavigationBarItem], visually
 * approximating it (pill indicator behind the icon when selected, same
 * label) while using a single [Modifier.combinedClickable] for both tap and
 * long-press -- deliberately not [NavigationBarItem] itself, which has no
 * long-press slot in its public API and whose internal `selectable` click
 * handling would compete with a second gesture detector layered on top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.DebugNewSessionItem(selected: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f, fill = true)
            .height(NavigationBarItemHeight)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(containerColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = contentColor)
        }
        Text(text = "New Session", color = contentColor, style = MaterialTheme.typography.labelMedium)
    }
}
