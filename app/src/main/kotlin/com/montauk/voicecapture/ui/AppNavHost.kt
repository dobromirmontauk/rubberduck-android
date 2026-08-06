package com.montauk.voicecapture.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.montauk.voicecapture.VoiceCaptureApp
import com.montauk.voicecapture.service.TooShortWarningStateHolder
import com.montauk.voicecapture.session.PendingRemovalHolder
import com.montauk.voicecapture.session.RecordingMode
import com.montauk.voicecapture.session.SwipeHintStateHolder
import com.montauk.voicecapture.ui.theme.VoiceCaptureTheme

/**
 * Hosts the nav graph + Material 3 bottom bar. The bottom bar is visible on
 * the sessions list, settings, and session-detail screens; it's hidden on
 * the recording screen (full-glance mode) and the sign-in placeholder.
 */
@Composable
fun AppNavHost(
    startDestination: String,
    onNewSessionTapped: (injectAssetFileName: String?) -> Unit,
    onStopRecording: () -> Unit,
    onSetMode: (RecordingMode) -> Unit = {},
    onAddTag: (tag: String, tagId: String?) -> Unit = { _, _ -> },
    onRemoveTag: (tag: String) -> Unit = {},
    onSwapTag: (oldTag: String, newTag: String, newTagId: String?) -> Unit = { _, _, _ -> },
    onApproveTag: (tag: String) -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as VoiceCaptureApp
    var showLogoutDialog by remember { mutableStateOf(false) }
    // Debug-only (bead vn-edu.20): long-pressing the "New Session" tab opens
    // this instead of starting a live-mic recording -- see BottomNavBar.
    var showFixturePicker by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == Routes.SESSIONS || currentRoute == Routes.SETTINGS || currentRoute == Routes.SESSION_DETAIL
    // Read fresh on every recomposition (same pattern as SettingsScreen) rather than
    // cached in remembered state -- a sign-in/log-out always triggers a navigation,
    // which recomposes this whole function, so the 4th tab's label picks it up for free.
    val isConnectedToGithub = app.secretsStore.isConnectedToGithub()

    // Bead vn-edu.56: hosted at the Scaffold level (not inside RecordingScreen)
    // so the "Session too short to save" snackbar survives the Stop->Sessions
    // navigation that RecordingScreen's onStopRecording triggers immediately --
    // by the time this would show, the user is usually already looking at the
    // Sessions list, not the recording screen.
    val snackbarHostState = remember { SnackbarHostState() }
    val tooShortWarning by TooShortWarningStateHolder.state.collectAsStateWithLifecycle()
    LaunchedEffect(tooShortWarning) {
        if (tooShortWarning == null) return@LaunchedEffect
        // SnackbarDuration.Indefinite deliberately: the real timeout lives in
        // RecordingService/TooShortPolicy.WARNING_WINDOW_MS, not here. When
        // that window elapses (or "Save anyway" already resolved the
        // decision), tooShortWarning flips back to null, this LaunchedEffect
        // relaunches with warning == null, and cancelling the coroutine that
        // was suspended in showSnackbar() dismisses it automatically -- so
        // this composable never needs its own duplicate timer.
        val result = snackbarHostState.showSnackbar(
            message = "Session too short to save",
            actionLabel = "Save anyway",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            TooShortWarningStateHolder.saveAnyway()
        }
    }

    // Bead vn-edu.67: transient "Not yet integrated" hint when a right-swipe
    // (archive) settles back on an ineligible row -- no undo/execute
    // semantics at all, just a message shown once and cleared.
    val swipeHint by SwipeHintStateHolder.message.collectAsStateWithLifecycle()
    LaunchedEffect(swipeHint) {
        val hint = swipeHint ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = hint, duration = SnackbarDuration.Short)
        SwipeHintStateHolder.clear()
    }

    // Bead vn-edu.67 (asn-638: flush() -> flushAll(), now that multiple rows
    // can be pending at once): "the app backgrounds" leg of the no-lost-
    // commits guarantee -- the other leg is each pending row's own 10s
    // countdown (SessionListScreen's PendingSessionRow) and the "leaves the
    // Sessions screen" case (SessionListScreen's own DisposableEffect).
    // ON_STOP is the Activity going invisible -- multitasking away, screen
    // off, or an incoming call -- since there's no guarantee the process
    // (and this composition, and every pending row's countdown coroutine)
    // survives past that point.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) PendingRemovalHolder.flushAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun startNewSession(injectAssetFileName: String?) {
        // Permission-gated service start lives in MainActivity (it owns the
        // ActivityResult permission launcher); navigation lives here (this
        // composable owns the NavController). Navigate immediately regardless
        // of whether the permission prompt is about to show -- RecordingScreen
        // just renders "not recording yet" for the brief window until the
        // service actually starts.
        onNewSessionTapped(injectAssetFileName)
        navController.navigate(Routes.RECORDING) { launchSingleTop = true }
    }

    // Wraps the Scaffold (and therefore the bottom nav bar's chrome) in the
    // app's dark theme -- each screen composable also wraps itself in
    // VoiceCaptureTheme for previewability, but without this outer wrap the
    // NavigationBar/Scaffold surfaces fall back to Compose's default (light)
    // Material3 baseline theme since they sit outside any screen's own wrap.
    VoiceCaptureTheme {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    isConnectedToGithub = isConnectedToGithub,
                    onNewSession = { startNewSession(null) },
                    onNewSessionLongPress = { showFixturePicker = true },
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
                    onAuthTapped = {
                        if (isConnectedToGithub) {
                            showLogoutDialog = true
                        } else {
                            navController.navigate(Routes.LOGIN)
                        }
                    },
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
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                    onSwapTag = onSwapTag,
                    onApproveTag = onApproveTag,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
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
                SettingsScreen(
                    onRunSetupAgain = { navController.navigate(Routes.WIZARD) },
                    onConnectGithub = { navController.navigate(Routes.LOGIN) },
                )
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
                // Lands on Sessions, not Login (bead vn-edu.29 -- logging out never
                // gates the app; it's fully usable signed out, same as a fresh
                // install). Clears the entire back stack (whatever screen Log Out
                // was tapped from) rather than the fragile popUpTo(0) idiom.
                navController.navigate(Routes.SESSIONS) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            },
            onDismiss = { showLogoutDialog = false },
        )
    }

    if (showFixturePicker) {
        FixturePickerDialog(
            onPick = { fixture ->
                showFixturePicker = false
                startNewSession(fixture.assetFileName)
            },
            onDismiss = { showFixturePicker = false },
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
