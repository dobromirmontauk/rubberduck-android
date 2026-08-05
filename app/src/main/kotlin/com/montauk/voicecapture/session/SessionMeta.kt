package com.montauk.voicecapture.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry in [SessionMeta.modes]: `{"t_ms": 0, "mode": "listen"}`. */
@Serializable
data class SessionModeEntry(
    @SerialName("t_ms") val tMs: Long,
    @SerialName("mode") val mode: String,
)

/**
 * Mirrors the `meta.json` schema documented in the voice-vault ingest
 * contract (voice-vault repo: docs/ingest-contract.md). Field names are
 * snake_case on the wire to match that contract exactly; keep this class and
 * the vault's schema in lockstep if either changes.
 *
 * `stt` is null until a transcription pipeline has processed the session --
 * this app only ever writes null here; the vault side fills it in.
 *
 * `modes` is an optional field per the contract's optional-field rules: this
 * app always populates it (at minimum, the initial mode at t_ms=0), but a
 * reader must tolerate its absence for sessions written before the mode
 * switcher existed.
 */
@Serializable
data class SessionMeta(
    @SerialName("session_id") val sessionId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("device") val device: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("stt") val stt: String? = null,
    @SerialName("modes") val modes: List<SessionModeEntry>? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,
)
