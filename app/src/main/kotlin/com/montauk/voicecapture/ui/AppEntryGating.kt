package com.montauk.voicecapture.ui

/**
 * Pure decision logic for "which screen does the app open on" and "where
 * does a successful GitHub connect land" -- pulled out of [MainActivity] and
 * [AppNavHost] so these rules are unit-testable without an Android Context
 * or a Compose test rule. No Android imports on purpose.
 */
object AppEntryGating {

    /**
     * [MainActivity]'s cold-start destination. Bead vn-edu.29: there is no
     * login/wizard gate on launch -- recording, playback, and the session
     * list are all fully functional signed out (sessions simply stay LOCAL,
     * see [com.montauk.voicecapture.service.RecordingService]), so sign-in
     * state never factors into where the app opens. An in-progress recording
     * is the only thing that wins over Sessions: a relaunch must never land
     * anywhere else while a session is still running.
     */
    fun startDestination(isRecording: Boolean): String =
        if (isRecording) Routes.RECORDING else Routes.SESSIONS

    /**
     * Where the connect flow (Settings' "Connect GitHub", or the bottom
     * nav's "Sign In" tab -- both land on [Routes.LOGIN]) goes after a
     * successful sign-in: the wizard's first-run flag decides between the
     * wizard (first-ever connect, needs a vault picked before uploads can
     * start) and Sessions (a repeat connect skips straight through).
     */
    fun postLoginDestination(setupWizardCompleted: Boolean): String =
        if (setupWizardCompleted) Routes.SESSIONS else Routes.WIZARD
}
