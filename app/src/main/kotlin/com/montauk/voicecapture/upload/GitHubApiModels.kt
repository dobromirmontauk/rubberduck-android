package com.montauk.voicecapture.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal response shapes for the two GitHub HTTP surfaces [GitHubBundleUploader]
 * talks to: the Git Data API (refs/commits/trees/blobs) and the Git LFS batch
 * API. Only fields this uploader reads are modelled -- `ignoreUnknownKeys` is
 * set on the shared [uploaderJson] instance so GitHub can add fields freely.
 */
// encodeDefaults is required: request models rely on defaulted fields that the
// server treats as mandatory (e.g. LfsBatchRequest.operation — omitting it is a 422).
internal val uploaderJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class GitRefResponse(
    val ref: String = "",
    @SerialName("object") val obj: GitRefObject = GitRefObject(),
)

@Serializable
internal data class GitRefObject(val sha: String = "", val type: String = "")

@Serializable
internal data class GitCommitResponse(val sha: String = "", val tree: GitTreeRef = GitTreeRef())

@Serializable
internal data class GitTreeRef(val sha: String = "")

@Serializable
internal data class GitBlobResponse(val sha: String = "")

@Serializable
internal data class GitTreeResponse(val sha: String = "")

@Serializable
internal data class GitCommitCreateResponse(val sha: String = "")

@Serializable
internal data class ContentsResponse(val content: String = "", val encoding: String = "")

@Serializable
internal data class LfsBatchResponse(val objects: List<LfsObjectResult> = emptyList())

@Serializable
internal data class LfsObjectResult(
    val oid: String = "",
    val size: Long = 0,
    val actions: LfsActions? = null,
    val error: LfsError? = null,
)

@Serializable
internal data class LfsActions(val upload: LfsAction? = null, val verify: LfsAction? = null)

@Serializable
internal data class LfsAction(
    val href: String = "",
    val header: Map<String, String> = emptyMap(),
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
internal data class LfsError(val code: Int = 0, val message: String = "")

/** One entry in a `git/trees` create-tree request body. */
@Serializable
internal data class TreeEntry(
    val path: String,
    val mode: String = "100644",
    val type: String = "blob",
    val sha: String,
)

// --- Request bodies ---

@Serializable
internal data class CreateBlobRequest(val content: String, val encoding: String = "base64")

@Serializable
internal data class CreateTreeRequest(@SerialName("base_tree") val baseTree: String, val tree: List<TreeEntry>)

@Serializable
internal data class CreateCommitRequest(val message: String, val tree: String, val parents: List<String>)

@Serializable
internal data class UpdateRefRequest(val sha: String, val force: Boolean = false)

@Serializable
internal data class LfsBatchRequest(
    val operation: String = "upload",
    val transfers: List<String> = listOf("basic"),
    val objects: List<LfsObjectRequest>,
)

@Serializable
internal data class LfsObjectRequest(val oid: String, val size: Long)

@Serializable
internal data class LfsVerifyRequest(val oid: String, val size: Long)
