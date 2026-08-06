package com.montauk.voicecapture.summary

/**
 * Renders the live summary's current bullets as `summary.md` (bead
 * asn-evl) -- rewritten in full every round rather than appended to, since
 * the running bullet list is small (a handful to a few dozen short lines)
 * and this keeps the file always exactly reflecting [SummaryRoundResult.bullets],
 * with no risk of a stale tail from an [AppendOnlyBulletMerge] edit case
 * this class doesn't need to know about. Uploaded with the rest of the
 * session bundle (see [com.montauk.voicecapture.upload.GitHubBundleUploader])
 * so the vault's organize pipeline can reuse it instead of re-summarizing
 * the transcript from scratch.
 */
object SummaryMarkdownWriter {
    /** Empty string (not a header-only stub) when [bullets] is empty -- a session with no summary yet uploads no meaningful summary.md content at all. */
    fun render(bullets: List<String>): String =
        if (bullets.isEmpty()) "" else bullets.joinToString("\n") { "- $it" } + "\n"
}
