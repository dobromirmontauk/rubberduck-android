package com.montauk.voicecapture.ui

/** Nav-graph route constants, shared between [AppNavHost] and [BottomNavBar]. */
object Routes {
    const val RECORDING = "recording"
    const val SESSIONS = "sessions"
    const val SESSION_DETAIL = "sessionDetail/{sessionId}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"

    fun sessionDetail(sessionId: String) = "sessionDetail/$sessionId"
}
