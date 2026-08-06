package com.montauk.voicecapture.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A fragment's tag, per `docs/organization-json.md`'s `fragments[].tags[]` shape -- [tagId] is null for a loosely-matched, non-confident tag name. */
@Serializable
data class OrganizationTag(
    val name: String = "",
    @SerialName("tag_id") val tagId: String? = null,
)

/**
 * Where a fragment landed in `notes/`, per `fragments[].destination`. Absent
 * (null) on an excluded fragment -- see [OrganizationFragment.destination].
 */
@Serializable
data class OrganizationDestination(
    val path: String = "",
    val section: String = "",
    /** `"appended"` or `"new_file"` -- the schema doc's only two values. */
    val mode: String = "",
)

/** One topic fragment, per `docs/organization-json.md`'s `fragments[]` shape. */
@Serializable
data class OrganizationFragment(
    val index: Int = 0,
    @SerialName("t_start_ms") val tStartMs: Long = 0,
    @SerialName("t_end_ms") val tEndMs: Long = 0,
    val speaker: String? = null,
    val summary: String = "",
    val tags: List<OrganizationTag> = emptyList(),
    val destination: OrganizationDestination? = null,
    val notes: String? = null,
)

/** One unorganized span, per `docs/organization-json.md`'s `unassigned_spans[]` shape. */
@Serializable
data class OrganizationUnassignedSpan(
    @SerialName("t_start_ms") val tStartMs: Long = 0,
    @SerialName("t_end_ms") val tEndMs: Long = 0,
    val reason: String = "",
)

/** The run's roll-up counts, per `docs/organization-json.md`'s `totals` shape. */
@Serializable
data class OrganizationTotals(
    val fragments: Int = 0,
    @SerialName("notes_created") val notesCreated: Int = 0,
    @SerialName("notes_appended") val notesAppended: Int = 0,
    @SerialName("new_tags") val newTags: Int = 0,
    val excluded: Int = 0,
)

/**
 * Decoded `sessions/<id>/organization.json` (bead vn-edu.57), per the schema
 * at `voice-vault/docs/organization-json.md` (vn-vlt.2). `restructure_candidates`
 * is intentionally not modelled -- it's provenance for a future human-driven
 * branch+PR, nothing the Filed-under tab renders.
 *
 * [processingSummary] (bead vn-edu.60) is a new optional top-level field --
 * a few sentences the organize pipeline writes about how it processed the
 * session. Absent on any session organized before this field existed, which
 * is a normal, non-error case: [OrganizationDocumentParser.parse] normalizes
 * a present-but-blank value to null too, so callers only ever have to handle
 * "there's a summary" vs. "there isn't."
 */
@Serializable
data class OrganizationDocument(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("processing_summary") val processingSummary: String? = null,
    val fragments: List<OrganizationFragment> = emptyList(),
    @SerialName("unassigned_spans") val unassignedSpans: List<OrganizationUnassignedSpan> = emptyList(),
    val totals: OrganizationTotals? = null,
)

/**
 * Parser for `organization.json`. Returns null -- never throws -- on
 * blank/null input or malformed JSON, so callers ([FiledToRenderModel]) can
 * treat "doesn't parse" exactly like "doesn't exist" and fall back to
 * `organization.md`, per the schema doc's explicit contract that a missing
 * (or, by extension, unparseable) `organization.json` is never an error.
 */
object OrganizationDocumentParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): OrganizationDocument? {
        if (raw.isNullOrBlank()) return null
        val decoded = runCatching { json.decodeFromString<OrganizationDocument>(raw) }.getOrNull() ?: return null
        // A present-but-empty "processing_summary" is invalid, not a real
        // (if terse) summary -- treat it exactly like the field being absent
        // rather than rendering a blank line above the destination detail.
        return if (decoded.processingSummary.isNullOrBlank()) decoded.copy(processingSummary = null) else decoded
    }
}
