package com.montauk.voicecapture.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the `meta.json` schema documented in the voice-vault ingest
 * contract (voice-vault repo: docs/ingest-contract.md). Field names are
 * snake_case on the wire to match that contract exactly; keep this class and
 * the vault's schema in lockstep if either changes.
 *
 * `stt` is null until a transcription pipeline has processed the session --
 * this app only ever writes null here; the vault side fills it in.
 */
@Serializable
data class SessionMeta(
    @SerialName("session_id") val sessionId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("device") val device: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("stt") val stt: String? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,
)
