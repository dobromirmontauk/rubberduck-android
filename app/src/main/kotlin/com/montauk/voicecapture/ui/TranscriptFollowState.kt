package com.montauk.voicecapture.ui

/**
 * Pure follow/snap-back decision logic for the live-transcript pane's scroll
 * position (bead vn-edu.37), extracted so the "auto-follow the newest line,
 * but let a manual scroll-up stick until the reader has been idle for a bit"
 * rule is unit-testable without a live Compose scroll container.
 *
 * Two events drive it, both timestamped by the caller -- no internal clock,
 * same pattern as [com.montauk.voicecapture.service.SilenceDetector]:
 *  - [onManualScroll] whenever the user's own drag moves the list. Landing at
 *    the bottom resumes following immediately; landing away from the bottom
 *    stops following and starts an idle clock.
 *  - [onNewContent] whenever a new final line or partial update arrives.
 *    Returns whether the pane should (re)scroll to the newest line: true
 *    while already following, or once [idleMs] has passed with no further
 *    manual scroll since the last scroll-away -- at which point following
 *    resumes for this and subsequent content.
 */
class TranscriptFollowState(private val idleMs: Long = DEFAULT_IDLE_MS) {
    private var following = true
    private var scrolledAwayAtMs: Long? = null

    fun onManualScroll(nowMs: Long, isAtBottom: Boolean) {
        if (isAtBottom) {
            following = true
            scrolledAwayAtMs = null
        } else {
            following = false
            scrolledAwayAtMs = nowMs
        }
    }

    fun onNewContent(nowMs: Long): Boolean {
        if (following) return true
        val awayAtMs = scrolledAwayAtMs ?: run {
            following = true
            return true
        }
        if (nowMs - awayAtMs >= idleMs) {
            following = true
            scrolledAwayAtMs = null
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_IDLE_MS = 3_000L
    }
}
