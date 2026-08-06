package com.montauk.voicecapture.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Threshold band for the duck view's latency badge (design-board section 3,
 * "L3 the duck feels it"): ~1s amber, ~3s red + persistent. Bead asn-55q
 * (next wave) owns the real mic->partial / partial->final / tail->LLM
 * measurements and the HUD (L2) the badge opens on tap.
 */
enum class LatencySeverity { OK, WARN, CRITICAL }

/** [message] is the short "which stage is slow" label the badge shows, e.g. "transcription slow · 1.8s"; null when [severity] is OK. */
data class LatencyBadgeUiState(
    val severity: LatencySeverity = LatencySeverity.OK,
    val message: String? = null,
)

/**
 * Stub (bead asn-3sm reserves the slot + this shape; bead asn-55q wires the
 * real measurements and the HUD the badge's tap opens). Mirrors
 * [TagsStateHolder]'s no-bound-service pattern. Nothing in this codebase
 * calls [update] yet -- [severity] stays [LatencySeverity.OK] (badge
 * hidden) until asn-55q lands.
 */
object LatencyBadgeStateHolder {
    private val _state = MutableStateFlow(LatencyBadgeUiState())
    val state: StateFlow<LatencyBadgeUiState> = _state.asStateFlow()

    fun update(newState: LatencyBadgeUiState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = LatencyBadgeUiState()
    }
}
