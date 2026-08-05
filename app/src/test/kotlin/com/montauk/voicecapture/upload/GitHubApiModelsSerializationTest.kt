package com.montauk.voicecapture.upload

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for a real bug caught during wave-2 integration:
 * `uploaderJson` didn't set `encodeDefaults = true`, so every field left at
 * its Kotlin default silently vanished from the JSON body -- `operation`
 * and `transfers` (git-lfs batch request) and `encoding` (git blob create)
 * are all defaulted fields the *server* treats as required, so this wasn't
 * a cosmetic difference: the LFS batch API 422'd, and blob creation would
 * have silently written raw base64 *text* as the blob's literal content
 * instead of decoding it, corrupting every committed file.
 *
 * These tests assert on the exact serialized string rather than round-
 * tripping through the same [uploaderJson] instance being tested --
 * decoding back with the same misconfigured serializer would still "pass"
 * even with the bug present, since both sides agree on what's missing.
 */
class GitHubApiModelsSerializationTest {

    @Test
    fun `LfsBatchRequest serializes operation and transfers even though both are defaulted`() {
        val json = uploaderJson.encodeToString(
            LfsBatchRequest.serializer(),
            LfsBatchRequest(objects = listOf(LfsObjectRequest("deadbeef", 42L))),
        )

        assertTrue("expected \"operation\":\"upload\" in $json", json.contains(""""operation":"upload""""))
        assertTrue("expected \"transfers\":[\"basic\"] in $json", json.contains(""""transfers":["basic"]"""))
    }

    @Test
    fun `CreateBlobRequest serializes encoding even though it's defaulted`() {
        val json = uploaderJson.encodeToString(CreateBlobRequest.serializer(), CreateBlobRequest(content = "aGVsbG8="))

        assertTrue("expected \"encoding\":\"base64\" in $json", json.contains(""""encoding":"base64""""))
    }

    @Test
    fun `UpdateRefRequest serializes force even though it's defaulted to false`() {
        // force=false is the safety-critical default (never force-push); a
        // dropped field here would mean GitHub falls back to its own
        // default, which happens to also be false today but shouldn't be
        // relied upon silently.
        val json = uploaderJson.encodeToString(UpdateRefRequest.serializer(), UpdateRefRequest(sha = "abc123"))

        assertTrue("expected \"force\":false in $json", json.contains(""""force":false"""))
    }
}
