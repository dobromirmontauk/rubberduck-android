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
 * Pure Kotlin state machine behind [NotesCard]'s enter/hold/leave
 * choreography (bead asn-3sm design-board section 2, direction changed by
 * bead asn-dp2's v2 revision -- see that bead's NOTES): "duck plays 'taking
 * notes' -> notes card enters moving left-to-right over the word cloud,
 * newest bullet highlighted -> holds ~5-6s -> continues out to the right +
 * fades. Tap while up = pin open."
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

    // Bead asn-dp2: far enough in the past that a freshly-constructed
    // choreographer (never yet shown) reports isWritePoseActive == false at
    // any nowMs a real caller would ever pass -- see that function's KDoc.
    private var hiddenAtMs: Long = -EXIT_ANIMATION_MS * 1000

    /** A fresh summary snapshot landed -- reveals the card (unless already [NotesCardMode.PINNED], which a new bullet must not interrupt). */
    fun onSummaryUpdated(nowMs: Long) {
        if (mode == NotesCardMode.PINNED) return
        mode = NotesCardMode.SHOWN
        shownAtMs = nowMs
    }

    /** Re-evaluates the auto-hide window; call every frame while [mode] is [NotesCardMode.SHOWN]. */
    fun tick(nowMs: Long) {
        if (mode == NotesCardMode.SHOWN && nowMs - shownAtMs >= autoHideAfterMs) {
            hide(nowMs)
        }
    }

    /** Tapping the card while it's up: pins it open, or un-pins (hides) it if it was already pinned. */
    fun togglePin(nowMs: Long) {
        when (mode) {
            NotesCardMode.HIDDEN, NotesCardMode.SHOWN -> {
                mode = NotesCardMode.PINNED
                shownAtMs = nowMs
            }
            NotesCardMode.PINNED -> hide(nowMs)
        }
    }

    /** Swiping up anytime the card is hidden: a transient reveal that still auto-hides on its own window. */
    fun peek(nowMs: Long) {
        if (mode == NotesCardMode.HIDDEN) {
            mode = NotesCardMode.SHOWN
            shownAtMs = nowMs
        }
    }

    /**
     * Bead asn-dp2 v2: true from the instant the card starts entering until
     * [EXIT_ANIMATION_MS] after it fully hides -- the exact window the duck
     * should hold his WRITE pose (design board: "the duck keeps his write
     * pose the whole time the card is up"), covering the card's continued
     * slide-away + fade after [mode] itself has already flipped back to
     * [NotesCardMode.HIDDEN] (that flip fires the exit animation; it doesn't
     * mean the card has visually left yet).
     */
    fun isWritePoseActive(nowMs: Long): Boolean =
        mode != NotesCardMode.HIDDEN || (nowMs - hiddenAtMs) < EXIT_ANIMATION_MS

    private fun hide(nowMs: Long) {
        mode = NotesCardMode.HIDDEN
        hiddenAtMs = nowMs
    }

    companion object {
        const val DEFAULT_AUTO_HIDE_MS = 5_500L

        /** Storyboard v5.2 frame 7: "card continues out to the RIGHT ... + fades (250ms ease-in)". */
        const val EXIT_ANIMATION_MS = 250L
    }
}
