package com.montauk.voicecapture.vault

/**
 * What the Filed-to tab should render (bead vn-edu.57), decided purely from
 * the two artifact texts that may have come back from a fetch -- no I/O, no
 * Compose. `organization.json` wins whenever it's present and parses;
 * otherwise this falls back to the raw `organization.md` text, per
 * `voice-vault/docs/organization-json.md`'s explicit contract: "a session
 * archived before this schema existed has `organization.md` only... treat a
 * missing `organization.json` as fall back to rendering `organization.md`,
 * never as an error."
 */
sealed class FiledToRenderModel {
    data class Structured(val document: OrganizationDocument) : FiledToRenderModel()
    data class LegacyMarkdown(val text: String) : FiledToRenderModel()
    data object Unavailable : FiledToRenderModel()

    companion object {
        /**
         * [organizationJsonText] / [organizationMdText] are whatever
         * [com.montauk.voicecapture.VoiceCaptureApp.fetchVaultSessionArtifact]
         * returned for `organization.json` / `organization.md` respectively
         * (null on a fetch failure with nothing cached, same as "doesn't
         * exist" for this decision).
         */
        fun from(organizationJsonText: String?, organizationMdText: String?): FiledToRenderModel {
            val parsed = OrganizationDocumentParser.parse(organizationJsonText)
            if (parsed != null) return Structured(parsed)
            if (!organizationMdText.isNullOrBlank()) return LegacyMarkdown(organizationMdText)
            return Unavailable
        }
    }
}
