package com.montauk.voicecapture.duck

/** What [NotesCard] should currently render (bead asn-3sm, design-board section 2's choreography). */
enum class NotesCardMode {
    /** Nothing shown (just a slim always-there peek handle in the composable layer). */
    HIDDEN,

    /** Auto-revealed (a new summary landed, or the user peeked) -- will auto-hide after its window unless pinned first. */
    SHOWN,

    /** The user tapped the card while it was up -- stays open until tapped again. */
    PINNED,
}

/**
 * Pure Kotlin state machine behind [NotesCard]'s slide-up/hold/slide-away
 * choreography (bead asn-3sm, design-board section 2): "duck plays 'taking
 * notes' -> notes card slides up, newest bullet highlighted -> holds a few
 * seconds -> slides away. Tap while up = pin open; swipe up anytime = peek."
 *
 * No Compose/Android dependency -- same shape as [DuckAnimationEngine],
 * unit-testable without Robolectric. [NotesCard] drives [onSummaryUpdated]
 * from [com.montauk.voicecapture.service.SummaryStateHolder] changes,
 * [tick] from its own frame loop (to fire the auto-hide), and
 * [togglePin]/[peek] from user gestures.
 */
class NotesCardChoreographer(private val autoHideAfterMs: Long = DEFAULT_AUTO_HIDE_MS) {
    var mode: NotesCardMode = NotesCardMode.HIDDEN
        private set
    private var shownAtMs: Long = 0L

    /** A fresh summary snapshot landed -- reveals the card (unless already [NotesCardMode.PINNED], which a new bullet must not interrupt). */
    fun onSummaryUpdated(nowMs: Long) {
        if (mode == NotesCardMode.PINNED) return
        mode = NotesCardMode.SHOWN
        shownAtMs = nowMs
    }

    /** Re-evaluates the auto-hide window; call every frame while [mode] is [NotesCardMode.SHOWN]. */
    fun tick(nowMs: Long) {
        if (mode == NotesCardMode.SHOWN && nowMs - shownAtMs >= autoHideAfterMs) {
            mode = NotesCardMode.HIDDEN
        }
    }

    /** Tapping the card while it's up: pins it open, or un-pins (hides) it if it was already pinned. */
    fun togglePin(nowMs: Long) {
        mode = when (mode) {
            NotesCardMode.HIDDEN -> NotesCardMode.PINNED
            NotesCardMode.SHOWN -> NotesCardMode.PINNED
            NotesCardMode.PINNED -> NotesCardMode.HIDDEN
        }
        shownAtMs = nowMs
    }

    /** Swiping up anytime the card is hidden: a transient reveal that still auto-hides on its own window. */
    fun peek(nowMs: Long) {
        if (mode == NotesCardMode.HIDDEN) {
            mode = NotesCardMode.SHOWN
            shownAtMs = nowMs
        }
    }

    companion object {
        const val DEFAULT_AUTO_HIDE_MS = 5_500L
    }
}
