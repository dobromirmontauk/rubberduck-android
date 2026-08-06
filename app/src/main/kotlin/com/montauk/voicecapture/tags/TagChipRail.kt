package com.montauk.voicecapture.tags

/**
 * Where a [TagRailChip] came from -- drives the recording screen's
 * outlined-vs-filled rendering (bead asn-45m spec: "suggested = outlined;
 * user-confirmed/user-added = filled").
 */
enum class RailChipSource { SUGGESTED, USER }

/**
 * Whether [TagRailChip.tagId] resolves to a real node in the vault's
 * currently-loaded tag tree (bead asn-0jk's locked tag-colors design):
 * [EXISTING] iff [TagRailChip.tagId] is non-null, [PROPOSED_NEW] otherwise --
 * covers both a scorer's explicit "this looks like a genuinely new topic"
 * proposal and the legacy free-form/no-vault case (nothing in `tags.yaml` to
 * match against either way, so from the vault's perspective this tag simply
 * doesn't exist yet). Wire value is the lowercase-with-underscore string
 * ([TagStatus.wireValue]), matching this app's other event-line string fields
 * (`"event"`, `"source"`) rather than Kotlin's default enum-name casing.
 */
enum class TagStatus { EXISTING, PROPOSED_NEW }

/** [TagStatus]'s `live-transcript.jsonl` wire representation -- see [TagStatus]'s KDoc. */
val TagStatus.wireValue: String
    get() = when (this) {
        TagStatus.EXISTING -> "existing"
        TagStatus.PROPOSED_NEW -> "proposed_new"
    }

/**
 * One entry in the merged suggested+user list [TagChipRail.chips] returns --
 * the shared, screen-agnostic unit two different views bind to: the
 * recording screen's editable chip rail (outlined for [RailChipSource.SUGGESTED],
 * filled for [RailChipSource.USER]) and, per bead asn-3sm, an animated-duck
 * word cloud (green for confirmed/high-confidence or approved, white for
 * unapproved existing candidates, purple for an unapproved new-tag proposal).
 *
 * [confidence] carries [DisplayedTag.confidence] through for [RailChipSource.SUGGESTED]
 * chips (word-cloud sizing/coloring by confidence) and is always null for
 * [RailChipSource.USER] chips -- a user's own pick has no scorer confidence,
 * it's simply confirmed.
 *
 * [approved] is `source == USER` verbatim -- there is no separate "approved"
 * bookkeeping in [TagChipRail]; a chip is approved exactly when the user has
 * acted on it (added, swapped-in, or, for a [TagStatus.PROPOSED_NEW] chip,
 * tapped to approve via [TagChipRail.onApprove]). Only meaningful for
 * [TagStatus.PROPOSED_NEW] -- approving an already-[TagStatus.EXISTING] tag
 * is a no-op concept, since it's already a real `tags.yaml` node either way.
 */
data class TagRailChip(
    val tag: String,
    val tagId: String? = null,
    val source: RailChipSource,
    val confidence: Double? = null,
) {
    val status: TagStatus get() = if (tagId != null) TagStatus.EXISTING else TagStatus.PROPOSED_NEW
    val approved: Boolean get() = source == RailChipSource.USER
}

/**
 * State machine behind the recording screen's editable tag chip rail (bead
 * asn-45m). Wraps the live scorer's suggested tags (fed via [onSuggested],
 * the same up-to-3 [DisplayedTag]s [com.montauk.voicecapture.service.TagsStateHolder]
 * already publishes) with the user's own edits -- add ([onAdd]), remove
 * ([onRemove]), swap ([onSwap]) -- merging them into one ordered [chips]
 * list: user chips first (in the order the user acted on them), then
 * whatever suggested chips are left, in the tracker's own rank order.
 *
 * **Sticky removal** (bead requirement: a chip the user dismissed must never
 * resurrect on its own): [removedKeys] remembers every tag text the user has
 * ever removed *this session*, keyed the same normalized way [TagTracker]
 * keys its own candidates -- [onSuggested] doesn't consult it directly, but
 * [chips] filters any still-removed key out of the suggested side even if
 * [onSuggested] keeps re-feeding that exact tag every scorer cycle. Re-adding
 * the same tag through [onAdd] (or picking it again through [onSwap]) clears
 * it from [removedKeys] -- an explicit user re-add is allowed to win over an
 * earlier removal.
 *
 * Pure Kotlin, no I/O, no Android/Compose dependency -- same shape as
 * [TagTracker], and deliberately NOT the thing that writes
 * `live-transcript.jsonl` lines; [com.montauk.voicecapture.service.RecordingService]
 * owns turning [onAdd]/[onRemove]/[onSwap]'s return value into a
 * user-attributed tags event line (see [TagsEventWriter.encodeUserEditLine])
 * -- this class only tracks the display list itself.
 *
 * Not thread-safe -- same single-owner-coroutine contract as [TagTracker];
 * [RecordingService] drives every mutating call from its own single-threaded
 * intent-handling path.
 */
class TagChipRail {
    private data class UserEntry(val tag: String, val tagId: String?)

    private var suggested: List<DisplayedTag> = emptyList()
    private val userEntries = mutableListOf<UserEntry>() // insertion order = display order
    private val removedKeys = mutableSetOf<String>() // sticky for this TagChipRail's lifetime (one recording session)

    /** User chips first (insertion order), then non-removed suggested chips (rank order, regardless of [onSuggested]'s own input order), duplicates on either side resolved in favor of the user chip. */
    fun chips(): List<TagRailChip> {
        val userChips = userEntries.map { TagRailChip(it.tag, it.tagId, RailChipSource.USER) }
        val userKeys = userEntries.map { normalize(it.tag) }.toSet()
        val suggestedChips = suggested
            .filter { normalize(it.tag) !in removedKeys && normalize(it.tag) !in userKeys }
            .sortedBy { it.rank }
            .map { TagRailChip(it.tag, it.tagId, RailChipSource.SUGGESTED, confidence = it.confidence) }
        return userChips + suggestedChips
    }

    /** The chip the filing-destination ribbon derives from -- first in display order, or null when the rail is empty. */
    fun primary(): TagRailChip? = chips().firstOrNull()

    /**
     * Feeds a fresh suggested-tags set, e.g. every time
     * [com.montauk.voicecapture.service.TagsStateHolder] changes. Does NOT
     * apply [removedKeys] itself -- that filtering happens lazily in
     * [chips], so a tag removed after being suggested, then suggested again
     * unchanged, still never resurrects (bead's sticky-removal requirement).
     */
    fun onSuggested(tags: List<DisplayedTag>) {
        suggested = tags
    }

    /**
     * User tapped the (+) chip (or the picker's free-form "Add" row) and
     * picked [tag]/[tagId]. No-op (returns false) if [tag] is already a user
     * chip by normalized text -- adding the same tag twice never duplicates
     * the chip or should emit a second event line. Un-removes [tag] if it had
     * previously been removed this session: an explicit re-add wins over an
     * earlier removal. Returns true only when [chips] actually changed as a
     * result, so a caller (e.g. [com.montauk.voicecapture.service.RecordingService])
     * knows whether to emit a user tags event line at all.
     */
    fun onAdd(tag: String, tagId: String? = null): Boolean {
        val key = normalize(tag)
        if (key.isEmpty() || userEntries.any { normalize(it.tag) == key }) return false
        userEntries += UserEntry(tag, tagId)
        removedKeys.remove(key)
        return true
    }

    /**
     * User tapped a chip's remove (✕) affordance. A user chip is dropped
     * from [userEntries] outright; a suggested chip is instead added to
     * [removedKeys] so it can never resurrect this session even if
     * [onSuggested] keeps re-feeding it -- see class KDoc. Returns true only
     * if [tag] was actually showing beforehand (false for a no-op remove of
     * something already gone).
     */
    fun onRemove(tag: String): Boolean {
        val key = normalize(tag)
        val wasUser = userEntries.removeAll { normalize(it.tag) == key }
        val wasSuggestedShowing = !wasUser && key !in removedKeys && suggested.any { normalize(it.tag) == key }
        if (wasSuggestedShowing) removedKeys += key
        return wasUser || wasSuggestedShowing
    }

    /**
     * User tapped a chip's body and picked [newTag]/[newTagId] from the
     * picker to replace [oldTag] -- an atomic remove-then-add (via [onRemove]
     * then [onAdd]) so the rail never has a frame with neither chip showing.
     * The swapped-in chip always lands as a [RailChipSource.USER] chip, even
     * when [newTag] is the *same* tag the user re-picked from the sheet --
     * per the bead's "user-confirmed... filled" language, going through the
     * picker at all is itself the confirming action. Returns true if either
     * half of the swap changed [chips].
     */
    fun onSwap(oldTag: String, newTag: String, newTagId: String? = null): Boolean {
        val removed = onRemove(oldTag)
        val added = onAdd(newTag, newTagId)
        return removed || added
    }

    /**
     * User tapped a [TagStatus.PROPOSED_NEW] chip's body to approve it in
     * place (bead asn-0jk: purple -> green, a single tap, no picker --
     * distinct from [onSwap], which a tap on an already-[TagStatus.EXISTING]
     * chip still opens) -- promotes that exact suggested proposal to a
     * [RailChipSource.USER] chip, same [TagRailChip.approved] == `source ==
     * USER` rule every other chip already follows. A no-op (returns false)
     * if [tag] isn't currently a still-suggested, still-[TagStatus.PROPOSED_NEW]
     * candidate -- e.g. it was already approved (already a user chip), never
     * suggested at all, or is tree-matched ([TagStatus.EXISTING], nothing to
     * approve).
     */
    fun onApprove(tag: String): Boolean {
        val key = normalize(tag)
        val candidate = suggested.find { normalize(it.tag) == key && it.tagId == null } ?: return false
        return onAdd(candidate.tag, tagId = null)
    }

    /** Normalized tag keys of every currently-[TagRailChip.approved] chip -- feeds [TagsEventWriter.encodeLine]'s model-snapshot lines so an untouched proposal keeps reporting `approved:false` without [com.montauk.voicecapture.service.RecordingService] reaching into chip internals itself. */
    fun approvedKeys(): Set<String> = chips().filter { it.approved }.map { normalize(it.tag) }.toSet()

    private fun normalize(tag: String): String = tag.trim().lowercase()
}
