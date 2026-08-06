package com.montauk.voicecapture.tags

import com.montauk.voicecapture.session.LiveTranscriptLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A `live-transcript.jsonl` event line for the current displayed-tags set,
 * appended every time that set changes: `{"t_ms": 1200, "event": "tags",
 * "tags": [{"tag": "kitchen-remodel", "tag_id": "t_01...", "confidence": 0.87, "rank": 1}]}`.
 * Bead vn-edu.47: `tag_id` is present (the vault's `tags.yaml` node id) only
 * when [com.montauk.voicecapture.tags.DisplayedTag.tagId] is non-null --
 * i.e. this tag was matched to an existing tree node, not a free-form
 * proposal or a legacy no-vault tag -- see [TagsEventWriter]'s `explicitNulls
 * = false` json config, which is what drops the key entirely rather than
 * writing `"tag_id":null`.
 *
 * Distinct in shape from [LiveTranscriptLine] (transcript segments) and from
 * [com.montauk.voicecapture.session.ModeEventLine] (mode changes) -- same
 * contract-tolerant pattern as that class: a reader that only cares about
 * transcript text decodes as [LiveTranscriptLine] and simply skips any line
 * that doesn't match that shape (see
 * [com.montauk.voicecapture.session.SessionStore.readTranscriptLines]). An
 * offline organizer that *does* care about tags derives per-tag intervals by
 * scanning consecutive "tags" event lines, and can now consume `tag_id`
 * directly as a pre-anchored segment hint (a follow-up vault-side bead,
 * per vn-edu.47's acceptance criteria) instead of re-matching tag text
 * against `tags.yaml` itself.
 *
 * **Bead asn-45m** extends this same line shape with a second, mutually
 * exclusive variant for a *user* edit on the recording screen's tag rail
 * ([TagChipRail]) rather than a fresh scorer snapshot: `{"t_ms": 4200,
 * "event": "tags", "source": "user", "added": [{"tag": "kitchen-remodel",
 * "tag_id": "t_01..."}], "removed": [{"tag": "gardening"}]}`. `source`/
 * `added`/`removed` are all absent from a model-emitted line (unset ->
 * dropped by `explicitNulls = false`, same as `tag_id` above) and `tags` is
 * absent from a user-emitted line for the same reason -- the two variants
 * never share a payload, only the `t_ms`/`event` envelope, so a reader
 * branches on whether `source` is present (absent/anything else => model
 * snapshot; `"user"` => edit) rather than needing a separate `event` value.
 * See [TagsEventWriter.encodeLine] vs. [TagsEventWriter.encodeUserEditLine].
 *
 * **Bead asn-0jk** (locked tag-colors design) adds `status` and `approved` to
 * both variants' tag references: `status` is always present, `"existing"`
 * when [tagId] is non-null or `"proposed_new"` otherwise (see [TagStatus]/
 * [TagStatus.wireValue]) -- the same rule [TagRailChip.status] uses, so a
 * [TagChipRail]-driven UI and the wire event agree by construction. `approved`
 * is present (non-null) only when `status` is `"proposed_new"`: `false` for
 * an untouched model proposal (every snapshot line keeps reporting this until
 * the user acts, so a reader of only the latest snapshot -- not the full
 * event history -- still knows an offline organizer must not create that tag
 * yet), `true` once approved. Omitted entirely for `"existing"`, where
 * approval has no meaning -- the tag already exists.
 */
@Serializable
data class TagEventEntry(
    val tag: String,
    @SerialName("tag_id") val tagId: String? = null,
    val confidence: Double,
    val rank: Int,
    val status: String,
    val approved: Boolean? = null,
)

/**
 * One tag reference inside a user-edit line's `added`/`removed` arrays
 * (bead asn-45m) -- deliberately lighter than [TagEventEntry]: a user's pick
 * carries neither a scorer confidence nor a rank, only which tag (and, when
 * it's a tree-matched node, its permanent [tagId]) the user added or removed.
 * [approved] (bead asn-0jk) is set only on an `added` entry representing an
 * approval of a previously-[TagStatus.PROPOSED_NEW] tag (see
 * [TagChipRail.onApprove]) -- null/omitted for every other add and for every
 * `removed` entry, where it has no meaning.
 */
@Serializable
data class UserTagRef(val tag: String, @SerialName("tag_id") val tagId: String? = null, val approved: Boolean? = null)

@Serializable
data class TagsEventLine(
    @SerialName("t_ms") val tMs: Long,
    val event: String = "tags",
    // Model-snapshot variant only -- null (and dropped from the wire) on a
    // user-edit line. Nullable rather than defaulting to emptyList() so a
    // user-edit line can omit the key entirely instead of writing "tags":[]
    // alongside its own added/removed -- see the class KDoc's two-variant contract.
    val tags: List<TagEventEntry>? = null,
    // User-edit variant only, always the literal "user" when present.
    val source: String? = null,
    val added: List<UserTagRef>? = null,
    val removed: List<UserTagRef>? = null,
)

object TagsEventWriter {
    // Compact (no pretty-print), matching ModeEventWriter/LiveTranscriptWriter:
    // one JSON object per physical line. encodeDefaults=true for the same
    // reason as ModeEventWriter -- "event" always carries "tags", which is
    // exactly the default-valued field kotlinx.serialization would otherwise
    // omit. explicitNulls=false (bead vn-edu.47, extended by asn-45m) drops
    // any null field's key entirely (TagEventEntry.tagId, and now
    // TagsEventLine.tags/source/added/removed on whichever variant leaves
    // them unset) rather than writing e.g. "tag_id":null -- see
    // TagsEventLineTest's exact-string assertions, both the pre-existing
    // model-snapshot ones and the new user-edit ones.
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /**
     * [approvedKeys] (bead asn-0jk, from [TagChipRail.approvedKeys]) is a set
     * of normalized (trim+lowercase) tag text the user has already approved
     * -- an entry's `status`/`approved` are derived independently of
     * [DisplayedTag] itself (which knows nothing about user approval): a
     * [DisplayedTag] with a non-null [DisplayedTag.tagId] is always
     * `"existing"` (approval doesn't apply, `approved` omitted); one with a
     * null [DisplayedTag.tagId] is `"proposed_new"`, `approved` true iff its
     * normalized [DisplayedTag.tag] is in [approvedKeys]. Defaults to empty
     * so existing callers/tests that don't care about approval can omit it.
     */
    fun encodeLine(tMs: Long, displayed: List<DisplayedTag>, approvedKeys: Set<String> = emptySet()): String {
        val line = TagsEventLine(
            tMs = tMs,
            tags = displayed.map { d ->
                val status = if (d.tagId != null) TagStatus.EXISTING else TagStatus.PROPOSED_NEW
                val approved = if (status == TagStatus.PROPOSED_NEW) d.tag.trim().lowercase() in approvedKeys else null
                TagEventEntry(
                    tag = d.tag,
                    tagId = d.tagId,
                    confidence = d.confidence,
                    rank = d.rank,
                    status = status.wireValue,
                    approved = approved,
                )
            },
        )
        return json.encodeToString(TagsEventLine.serializer(), line)
    }

    /**
     * Bead asn-45m: a user add/remove/swap on the recording screen's tag
     * rail ([TagChipRail]) is authoritative, unlike [encodeLine]'s scored
     * snapshot -- this appends `"source":"user"` plus `added`/`removed` (see
     * [UserTagRef]) so an offline organizer can treat it as a hard
     * constraint rather than a scored guess it's free to second-guess.
     * [added]/[removed] are passed through as real (possibly empty) lists,
     * never left null, so they always appear on the wire -- even a
     * pure-removal edit writes an empty `"added":[]` alongside a populated
     * `"removed":[...]`, rather than a reader having to treat "key absent"
     * and "empty array" as the same thing for this variant.
     */
    fun encodeUserEditLine(tMs: Long, added: List<UserTagRef>, removed: List<UserTagRef>): String {
        val line = TagsEventLine(tMs = tMs, source = "user", added = added, removed = removed)
        return json.encodeToString(TagsEventLine.serializer(), line)
    }
}
