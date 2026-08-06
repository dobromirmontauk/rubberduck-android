package com.montauk.voicecapture.testutil

import com.montauk.voicecapture.upload.BundleUploader
import java.io.File

/**
 * Records every [uploadBundle] call it receives instead of making a network
 * call -- used by vn-edu.55's delete-from-phone/bulk-archive tests to prove
 * those LOCAL-ONLY actions never touch the uploader, the same role
 * [FakeVaultSessionSource] plays for the INTEGRATED-status read path.
 */
class FakeBundleUploader : BundleUploader {
    val calls = mutableListOf<File>()

    override suspend fun uploadBundle(sessionDir: File): Result<Unit> {
        calls += sessionDir
        return Result.success(Unit)
    }
}
