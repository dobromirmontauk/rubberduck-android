package com.montauk.voicecapture.tags

/**
 * Derives the recording screen's "Filing to: ..." ribbon text (bead
 * asn-45m) from the tag rail's primary chip. The app has no real
 * tag->destination mapping yet (that's a vault-side concept the organize
 * skill owns end-to-end, and category folders like `notes/areas/` don't
 * mirror the tag tree's path 1:1 today -- e.g. the `mashgin` tag files under
 * `notes/areas/mashgin.md`, not `notes/work/mashgin.md`). Until that mapping
 * exists on the wire somewhere the app can read, this derives a *plausible*
 * destination string directly from the tag tree/free text instead, so the
 * ribbon has something concrete and live to show -- see [destinationFor]'s
 * KDoc for the exact derivation and its caveat.
 */
object TagFilingDestination {
    /** Ribbon text when the rail is empty -- mirrors the vault's own `inbox/` concept for not-yet-organized content (see voice-vault/CLAUDE.md) rather than inventing a new placeholder name. */
    const val NO_TAGS_DESTINATION = "notes/inbox.md"

    /**
     * [primary]'s destination preview: for a tree-matched chip ([TagRailChip.tagId]
     * non-null), [TagTree.path]'s full ancestor-to-leaf chain, e.g.
     * `"work/mashgin"` -> `"notes/work/mashgin.md"`; for a free-form chip (no
     * tagId, or a dangling/unresolvable id -- [TagTree.path] returns "" for
     * both), a slugified form of the chip's own text instead, e.g.
     * `"kitchen remodel"` -> `"notes/kitchen-remodel.md"`. [NO_TAGS_DESTINATION]
     * when [primary] is null (nothing in the rail yet).
     *
     * This is a live *preview* for the recording screen, not a promise about
     * where the vault's organize skill will actually file the session --
     * see the class KDoc's `mashgin` example of where the two diverge today.
     */
    fun destinationFor(primary: TagRailChip?, tree: TagTree): String {
        if (primary == null) return NO_TAGS_DESTINATION
        val treePath = primary.tagId?.let { tree.path(it) }?.takeIf { it.isNotBlank() }
        val slug = treePath ?: slugify(primary.tag)
        return "notes/$slug.md"
    }

    private fun slugify(text: String): String =
        text.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
