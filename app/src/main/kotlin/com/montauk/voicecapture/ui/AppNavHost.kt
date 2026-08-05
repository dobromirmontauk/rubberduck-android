package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * Hosts the nav graph + Material 3 bottom bar. The bottom bar is visible on
 * the sessions list, settings, and session-detail screens; it's hidden on
 * the recording screen (full-glance mode) and the sign-in placeholder.
 */
@Composable
fun AppNavHost(
    startDestination: String,
    onNewSessionTapped: () -> Unit,
    onStopRecording: () -> Unit,
    onSetMode: (RecordingMode) -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    var showLogoutDialog by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == Routes.SESSIONS || currentRoute == Routes.SETTINGS || currentRoute == Routes.SESSION_DETAIL

    // Wraps the Scaffold (and therefore the bottom nav bar's chrome) in the
    // app's dark theme -- each screen composable also wraps itself in
    // VoiceCaptureTheme for previewability, but without this outer wrap the
    // NavigationBar/Scaffold surfaces fall back to Compose's default (light)
    // Material3 baseline theme since they sit outside any screen's own wrap.
    VoiceCaptureTheme {
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onNewSession = {
                        // Permission-gated service start lives in MainActivity
                        // (it owns the ActivityResult permission launcher);
                        // navigation lives here (this composable owns the
                        // NavController). Navigate immediately regardless of
                        // whether the permission prompt is about to show --
                        // RecordingScreen just renders "not recording yet"
                        // for the brief window until the service actually starts.
                        onNewSessionTapped()
                        navController.navigate(Routes.RECORDING) { launchSingleTop = true }
                    },
                    onSessions = {
                        navController.navigate(Routes.SESSIONS) {
                            popUpTo(Routes.SESSIONS) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSettings = {
                        navController.navigate(Routes.SETTINGS) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onLogOut = { showLogoutDialog = true },
                )
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(Routes.RECORDING) {
                RecordingScreen(
                    onSetMode = onSetMode,
                    onStopRecording = {
                        onStopRecording()
                        // Pop up to (and including) any existing "sessions"
                        // entry, not just "recording" -- the back stack when
                        // Stop is tapped is [sessions, recording] (New Session
                        // pushes recording on top without popping sessions),
                        // so popping only "recording" would leave the OLD
                        // sessions entry underneath and stack a second, new
                        // one on top of it instead of replacing it. Two
                        // sessions entries isn't just wasteful -- the buried
                        // one can end up being what's effectively rendered
                        // stale, so this always collapses to exactly one.
                        navController.navigate(Routes.SESSIONS) {
                            popUpTo(Routes.SESSIONS) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.SESSIONS) {
                SessionListScreen(
                    onSessionClick = { sessionId -> navController.navigate(Routes.sessionDetail(sessionId)) },
                )
            }
            composable(
                route = Routes.SESSION_DETAIL,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                if (sessionId != null) {
                    SessionDetailScreen(sessionId = sessionId, onBack = { navController.popBackStack() })
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onRunSetupAgain = { navController.navigate(Routes.WIZARD) })
            }
            composable(Routes.LOGIN) {
                LoginScreen(
                    onSignedIn = {
                        val next = AppEntryGating.postLoginDestination(app.secretsStore.setupWizardCompleted)
                        navController.navigate(next) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.WIZARD) {
                SetupWizardScreen(
                    onFinished = {
                        navController.navigate(Routes.SESSIONS) {
                            popUpTo(Routes.WIZARD) { inclusive = true }
                        }
                    },
                )
            }
        }
    }

    if (showLogoutDialog) {
        LogoutConfirmDialog(
            onConfirm = {
                showLogoutDialog = false
                app.secretsStore.isSignedOut = true
                app.refreshBundleUploader()
                navController.navigate(Routes.LOGIN) {
                    // Clears the entire back stack (whatever screen Log Out was
                    // tapped from) rather than the fragile popUpTo(0) idiom.
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            },
            onDismiss = { showLogoutDialog = false },
        )
    }
    }
}

@Composable
private fun LogoutConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log out?") },
        text = { Text("Recording keeps working locally. Upload and transcription pause until you sign in again.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Log out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
