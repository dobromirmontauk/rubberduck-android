package com.montauk.voicecapture.ui

/**
 * Pure decision logic for "which screen does the app open on" and "where
 * does a successful sign-in land" -- pulled out of [MainActivity] and
 * [AppNavHost] so these rules are unit-testable without an Android Context
 * or a Compose test rule. No Android imports on purpose.
 */
object AppEntryGating {

    /**
     * [MainActivity]'s cold-start destination. Order matters: an in-progress
     * recording always wins over the wizard -- the wizard must never be the
     * thing standing between a relaunch and an already-running session (see
     * vn-edu.15's "wizard never blocks recording"). The wizard itself only
     * triggers for a real, user-entered GitHub sign-in ([hasSignedInGithub]) --
     * a `local.properties`/BuildConfig-only dev build never routes through
     * login, so it never sees the wizard either (vn-edu.14's "dev flow
     * unbroken" requirement).
     */
    fun startDestination(
        isSignedOut: Boolean,
        isRecording: Boolean,
        setupWizardCompleted: Boolean,
        hasSignedInGithub: Boolean,
    ): String = when {
        isSignedOut -> Routes.LOGIN
        isRecording -> Routes.RECORDING
        !setupWizardCompleted && hasSignedInGithub -> Routes.WIZARD
        else -> Routes.SESSIONS
    }

    /**
     * Where the login screen navigates after a successful sign-in: the
     * wizard's first-run flag decides between the wizard (first-ever
     * sign-in) and Sessions (a returning signed-in user skips straight
     * through, per vn-edu.14's acceptance criteria).
     */
    fun postLoginDestination(setupWizardCompleted: Boolean): String =
        if (setupWizardCompleted) Routes.SESSIONS else Routes.WIZARD
}
