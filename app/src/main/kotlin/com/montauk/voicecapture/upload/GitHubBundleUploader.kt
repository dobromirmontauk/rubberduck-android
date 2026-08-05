package com.montauk.voicecapture.upload

import android.util.Log
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Ships a finalized session bundle to `dobromirmontauk/voice-vault` (private)
 * as `inbox/<session-id>/{audio.<ext>, live-transcript.jsonl, meta.json}` in
 * one commit on `main`, pure HTTPS -- no git binary.
 *
 * Two GitHub HTTP surfaces are involved:
 *  - The **Git LFS batch API** (`<repo>.git/info/lfs/objects/batch`) for the
 *    audio file, which routinely runs tens of MB -- well past what a base64
 *    inline blob in the contents/Data API comfortably handles. The object
 *    that actually lands in the tree is the small LFS *pointer* text, per
 *    the Git LFS spec.
 *  - The **Git Data API** (refs/commits/trees/blobs) to build and land the
 *    one commit containing the pointer file, `meta.json`, and (if present)
 *    `live-transcript.jsonl` against `base_tree` = current HEAD's tree.
 *
 * On a 409/422 ref-update race (another writer landed a commit first), the
 * ref/tree are refetched and the tree rebuilt once, then retried once more.
 * Idempotency: [checkAlreadyUploaded] treats a 200 from the contents API on
 * `inbox/<session-id>` as "already landed" and skips straight to success --
 * safe to call again after a crash mid-upload, since the commit step is
 * atomic (no partial `inbox/<id>/` can exist remotely).
 */
class GitHubBundleUploader(
    private val token: String,
    private val owner: String = "dobromirmontauk",
    private val repo: String = "voice-vault",
    private val branch: String = "main",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS) // audio.ogg can be tens of MB on a slow uplink
        .build(),
    private val apiBaseUrl: String = "https://api.github.com",
    private val lfsBaseUrl: String = "https://github.com",
) : BundleUploader {

    private companion object {
        const val TAG = "GitHubBundleUploader"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val LFS_MEDIA_TYPE = "application/vnd.git-lfs+json".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }

    private fun apiAuthHeader() = "Bearer $token"

    // GitHub's LFS HTTP endpoint authenticates the same way `git`'s own HTTPS
    // transport does for a PAT: HTTP Basic auth, token as the password, and
    // "x-access-token" as a placeholder username (GitHub ignores the username
    // for PATs -- this is the same convention actions/checkout uses).
    private fun lfsAuthHeader() = Credentials.basic("x-access-token", token)

    override suspend fun uploadBundle(sessionDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.failure(UploadError.AuthFailed())
        val sessionId = sessionDir.name
        runCatching {
            if (checkAlreadyUploaded(sessionId)) {
                Log.i(TAG, "inbox/$sessionId already exists at HEAD; treating as already uploaded")
                return@runCatching
            }

            val audioFile = sessionDir.listFiles { f -> f.isFile && f.name.startsWith("audio.") }?.firstOrNull()
                ?: error("no audio.<ext> file found in $sessionDir")
            val metaFile = File(sessionDir, "meta.json")
            require(metaFile.exists()) { "meta.json missing from $sessionDir" }
            val transcriptFile = File(sessionDir, "live-transcript.jsonl").takeIf { it.exists() && it.length() > 0 }

            val audioExt = audioFile.extension
            val useLfs = gitattributesTracksExtension(audioExt)
            val audioBlobBytes: ByteArray = if (useLfs) {
                val oid = GitHubLfsPointer.computeOid(audioFile)
                uploadToLfs(oid, audioFile)
                GitHubLfsPointer.pointerText(oid).toByteArray(Charsets.UTF_8)
            } else {
                Log.w(
                    TAG,
                    "*.$audioExt is not LFS-tracked in $owner/$repo's .gitattributes; " +
                        "uploading $sessionId's audio as a regular (non-LFS) blob instead",
                )
                audioFile.readBytes()
            }

            commitBundle(
                sessionId = sessionId,
                audioPath = "inbox/$sessionId/audio.$audioExt",
                audioBytes = audioBlobBytes,
                metaBytes = metaFile.readBytes(),
                transcriptBytes = transcriptFile?.readBytes(),
            )
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { e ->
                Log.e(TAG, "uploadBundle failed for $sessionId", e)
                Result.failure(classifyError(e))
            },
        )
    }

    private fun classifyError(e: Throwable): UploadError = when (e) {
        is UploadError -> e
        is java.io.IOException -> UploadError.NetworkUnavailable()
        else -> UploadError.Other(e.message ?: "upload failed", e)
    }

    /** Contents-API existence check: 200 = bundle already landed at HEAD, 404 = not yet. */
    internal fun checkAlreadyUploaded(sessionId: String): Boolean {
        val request = apiRequest("/repos/$owner/$repo/contents/inbox/$sessionId").build()
        httpClient.newCall(request).execute().use { response ->
            return when (response.code) {
                200 -> true
                404 -> false
                401, 403 -> throw UploadError.AuthFailed()
                else -> throw UploadError.Other("unexpected status ${response.code} checking inbox/$sessionId")
            }
        }
    }

    /** Best-effort: if we can't read `.gitattributes` for any reason, fall back to a regular (non-LFS) blob. */
    private fun gitattributesTracksExtension(ext: String): Boolean {
        val request = apiRequest("/repos/$owner/$repo/contents/.gitattributes").build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string() ?: return@use false
                val contents = uploaderJson.decodeFromString(ContentsResponse.serializer(), body)
                val decoded = String(Base64.getMimeDecoder().decode(contents.content.replace("\n", "")), Charsets.UTF_8)
                decoded.lineSequence().any { line ->
                    val trimmed = line.trim()
                    trimmed.startsWith("*.$ext") && trimmed.contains("filter=lfs")
                }
            }
        }.getOrDefault(false)
    }

    private fun uploadToLfs(oid: GitHubLfsPointer.Oid, audioFile: File) {
        val batchBody = uploaderJson.encodeToString(
            LfsBatchRequest.serializer(),
            LfsBatchRequest(objects = listOf(LfsObjectRequest(oid.sha256Hex, oid.sizeBytes))),
        )
        val batchRequest = Request.Builder()
            .url("$lfsBaseUrl/$owner/$repo.git/info/lfs/objects/batch")
            .addHeader("Authorization", lfsAuthHeader())
            .addHeader("Accept", "application/vnd.git-lfs+json")
            .post(batchBody.toRequestBody(LFS_MEDIA_TYPE))
            .build()

        val batchResponse = httpClient.newCall(batchRequest).execute().use { response ->
            if (!response.isSuccessful) throw UploadError.Other("LFS batch request failed: HTTP ${response.code}")
            uploaderJson.decodeFromString(LfsBatchResponse.serializer(), response.body?.string().orEmpty())
        }

        val result = batchResponse.objects.firstOrNull { it.oid == oid.sha256Hex }
            ?: throw UploadError.Other("LFS batch response had no entry for oid ${oid.sha256Hex}")
        result.error?.let { throw UploadError.Other("LFS batch error ${it.code}: ${it.message}") }

        val uploadAction = result.actions?.upload
        if (uploadAction != null) {
            val putBuilder = Request.Builder().url(uploadAction.href)
            uploadAction.header.forEach { (k, v) -> putBuilder.addHeader(k, v) }
            val putRequest = putBuilder.put(audioFile.asRequestBody(OCTET_STREAM)).build()
            httpClient.newCall(putRequest).execute().use { response ->
                if (!response.isSuccessful) throw UploadError.Other("LFS object PUT failed: HTTP ${response.code}")
            }
        } else {
            Log.i(TAG, "LFS server already has oid ${oid.sha256Hex}; skipping re-upload")
        }

        result.actions?.verify?.let { verifyAction ->
            val verifyBody = uploaderJson.encodeToString(LfsVerifyRequest.serializer(), LfsVerifyRequest(oid.sha256Hex, oid.sizeBytes))
            val verifyBuilder = Request.Builder().url(verifyAction.href)
            verifyAction.header.forEach { (k, v) -> verifyBuilder.addHeader(k, v) }
            val verifyRequest = verifyBuilder.post(verifyBody.toRequestBody(JSON_MEDIA_TYPE)).build()
            httpClient.newCall(verifyRequest).execute().use { response ->
                if (!response.isSuccessful) throw UploadError.Other("LFS verify failed: HTTP ${response.code}")
            }
        }
    }

    private fun commitBundle(sessionId: String, audioPath: String, audioBytes: ByteArray, metaBytes: ByteArray, transcriptBytes: ByteArray?) {
        var attempt = 0
        while (true) {
            attempt++
            val headSha = getRef()
            val baseTreeSha = getCommitTree(headSha)

            val entries = mutableListOf(
                TreeEntry(path = audioPath, sha = createBlob(audioBytes)),
                TreeEntry(path = "inbox/$sessionId/meta.json", sha = createBlob(metaBytes)),
            )
            transcriptBytes?.let { entries += TreeEntry(path = "inbox/$sessionId/live-transcript.jsonl", sha = createBlob(it)) }

            val newTreeSha = createTree(baseTreeSha, entries)
            val newCommitSha = createCommit("capture: session $sessionId lands in inbox", newTreeSha, listOf(headSha))

            updateRef(newCommitSha).use { response ->
                if (response.isSuccessful) return
                if ((response.code == 409 || response.code == 422) && attempt == 1) {
                    Log.i(TAG, "ref update race (HTTP ${response.code}) landing $sessionId; rebuilding tree against current HEAD and retrying once")
                    return@use // falls through to the while loop's next iteration
                }
                throw UploadError.Other("ref update failed: HTTP ${response.code} ${response.body?.string()}")
            }
        }
    }

    private fun getRef(): String {
        val request = apiRequest("/repos/$owner/$repo/git/refs/heads/$branch").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UploadError.Other("GET ref failed: HTTP ${response.code}")
            return uploaderJson.decodeFromString(GitRefResponse.serializer(), response.body?.string().orEmpty()).obj.sha
        }
    }

    private fun getCommitTree(commitSha: String): String {
        val request = apiRequest("/repos/$owner/$repo/git/commits/$commitSha").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UploadError.Other("GET commit failed: HTTP ${response.code}")
            return uploaderJson.decodeFromString(GitCommitResponse.serializer(), response.body?.string().orEmpty()).tree.sha
        }
    }

    private fun createBlob(bytes: ByteArray): String {
        val body = uploaderJson.encodeToString(
            CreateBlobRequest.serializer(),
            CreateBlobRequest(content = Base64.getEncoder().encodeToString(bytes)),
        )
        val request = apiRequest("/repos/$owner/$repo/git/blobs").post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UploadError.Other("create blob failed: HTTP ${response.code}")
            return uploaderJson.decodeFromString(GitBlobResponse.serializer(), response.body?.string().orEmpty()).sha
        }
    }

    private fun createTree(baseTree: String, entries: List<TreeEntry>): String {
        val body = uploaderJson.encodeToString(CreateTreeRequest.serializer(), CreateTreeRequest(baseTree, entries))
        val request = apiRequest("/repos/$owner/$repo/git/trees").post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UploadError.Other("create tree failed: HTTP ${response.code}")
            return uploaderJson.decodeFromString(GitTreeResponse.serializer(), response.body?.string().orEmpty()).sha
        }
    }

    private fun createCommit(message: String, tree: String, parents: List<String>): String {
        val body = uploaderJson.encodeToString(CreateCommitRequest.serializer(), CreateCommitRequest(message, tree, parents))
        val request = apiRequest("/repos/$owner/$repo/git/commits").post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UploadError.Other("create commit failed: HTTP ${response.code}")
            return uploaderJson.decodeFromString(GitCommitCreateResponse.serializer(), response.body?.string().orEmpty()).sha
        }
    }

    /** Never force-pushed: a losing race is expected to fail with 409/422, not clobber a concurrent writer. */
    private fun updateRef(sha: String): Response {
        val body = uploaderJson.encodeToString(UpdateRefRequest.serializer(), UpdateRefRequest(sha, force = false))
        val request = apiRequest("/repos/$owner/$repo/git/refs/heads/$branch", method = "PATCH", body = body).build()
        return httpClient.newCall(request).execute()
    }

    private fun apiRequest(path: String, method: String = "GET", body: String? = null): Request.Builder {
        val builder = Request.Builder()
            .url("$apiBaseUrl$path")
            .addHeader("Authorization", apiAuthHeader())
            .addHeader("Accept", "application/vnd.github+json")
        return when (method) {
            "PATCH" -> builder.patch((body ?: "").toRequestBody(JSON_MEDIA_TYPE))
            else -> builder.get()
        }
    }
}
