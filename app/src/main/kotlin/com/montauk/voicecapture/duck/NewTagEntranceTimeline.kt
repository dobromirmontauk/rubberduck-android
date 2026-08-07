package com.montauk.voicecapture.duck

/**
 * Phases of a brand-new tag's entrance into the thought cloud (bead asn-bmq,
 * storyboard v5.2's "New-tag entrance" demo -- "a new idea being born,
 * drawn like a cartoon piece by piece"): the word is handwritten
 * letter-by-letter, as if the duck is writing it down ([WRITE]), crossfades
 * into the cloud's normal bold typography with its status badge ([MORPH]),
 * then translates+scales from wherever it was written into its assigned
 * [CloudSlot][com.montauk.voicecapture.duck.ThoughtCloud]'s
 * position ([DRIFT]). [SETTLED] is everything after -- steady-state, same
 * rendering an already-established word has always had.
 *
 * Only ever applies to a word the thought cloud has never shown before this
 * session -- a word re-entering the top set after a confidence dip, or one
 * whose [com.montauk.voicecapture.tags.TagRailChip.approved] flips (purple
 * -> green), does NOT replay this; see [ThoughtCloud]'s own KDoc for how it
 * tracks "first ever seen" per word key, separately from this pure timeline.
 */
enum class NewTagEntrancePhase { WRITE, MORPH, DRIFT, SETTLED }

/**
 * Pure function of elapsed time -- no Compose/Android dependency, same
 * "plain Kotlin state machine" shape as
 * [com.montauk.voicecapture.tags.TagChipRail] -- so the phase timings
 * themselves are trivially unit-testable without a real (or simulated)
 * animation clock. [ThoughtCloud] samples this once per frame via
 * [at], feeding it real elapsed wall-clock time since the word first
 * appeared.
 */
object NewTagEntranceTimeline {
    /** Storyboard v5.2: "WRITE ~2.5s letter-stepped reveal with pencil at the edge." */
    const val WRITE_MS = 2500L

    /** Storyboard v5.2: "MORPH ~0.8s crossfade." */
    const val MORPH_MS = 800L

    /** Storyboard v5.2: "DRIFT ~1.5s translate+scale to final slot." */
    const val DRIFT_MS = 1500L

    /** [WRITE_MS] + [MORPH_MS] + [DRIFT_MS] -- elapsed time at which a word is fully [NewTagEntrancePhase.SETTLED]. */
    const val TOTAL_MS = WRITE_MS + MORPH_MS + DRIFT_MS

    /** Reduced-motion fallback (bead asn-bmq: "Reduced-motion setting shows a simple fade-in instead") -- short, no letter reveal, no drift. */
    const val REDUCED_MOTION_FADE_MS = 250L

    /**
     * One sampled instant of a word's entrance.
     *
     * [phaseProgress] is 0f..1f through [phase] specifically (not through
     * the whole entrance) -- 1f once [phase] is [NewTagEntrancePhase.SETTLED].
     * [lettersRevealed] is only meaningful during [NewTagEntrancePhase.WRITE]
     * (how many of the word's characters have been drawn so far); it is the
     * full text length in every other phase, including the reduced-motion
     * fallback, which never plays a letter-by-letter reveal at all.
     */
    data class Sample(
        val phase: NewTagEntrancePhase,
        val phaseProgress: Float,
        val lettersRevealed: Int,
    )

    /**
     * Samples the entrance timeline [elapsedMs] after a word first appeared.
     * [reducedMotion] collapses [WRITE]/[MORPH]/[DRIFT] into a single
     * [REDUCED_MOTION_FADE_MS] fade -- modeled as a truncated [MORPH] (the
     * "crossfade to normal typography" phase) so callers don't need a
     * separate reduced-motion rendering path, just a shorter one that skips
     * [WRITE] and [DRIFT] entirely.
     */
    fun at(elapsedMs: Long, text: String, reducedMotion: Boolean): Sample {
        if (reducedMotion) {
            val t = (elapsedMs.toFloat() / REDUCED_MOTION_FADE_MS).coerceIn(0f, 1f)
            val phase = if (elapsedMs >= REDUCED_MOTION_FADE_MS) NewTagEntrancePhase.SETTLED else NewTagEntrancePhase.MORPH
            return Sample(phase, t, text.length)
        }
        return when {
            elapsedMs < WRITE_MS -> {
                val t = (elapsedMs.toFloat() / WRITE_MS).coerceIn(0f, 1f)
                val revealed = (t * text.length).toInt().coerceIn(0, text.length)
                Sample(NewTagEntrancePhase.WRITE, t, revealed)
            }
            elapsedMs < WRITE_MS + MORPH_MS -> {
                val t = ((elapsedMs - WRITE_MS).toFloat() / MORPH_MS).coerceIn(0f, 1f)
                Sample(NewTagEntrancePhase.MORPH, t, text.length)
            }
            elapsedMs < TOTAL_MS -> {
                val t = ((elapsedMs - WRITE_MS - MORPH_MS).toFloat() / DRIFT_MS).coerceIn(0f, 1f)
                Sample(NewTagEntrancePhase.DRIFT, t, text.length)
            }
            else -> Sample(NewTagEntrancePhase.SETTLED, 1f, text.length)
        }
    }
}
