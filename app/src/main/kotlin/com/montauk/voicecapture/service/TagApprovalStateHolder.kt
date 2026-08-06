package com.montauk.voicecapture.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-local record of which proposed-new tags the user has tapped to
 * approve (bead asn-0jk / asn-3sm, design-board section 5): a PURPLE
 * proposed-new thought-cloud word turns GREEN the moment it's tapped.
 * Keyed the same normalized way [com.montauk.voicecapture.tags.TagTracker]
 * keys its own candidates, so approval survives re-ranking/redisplay of the
 * same tag text.
 *
 * This is NOT yet the durable `approved:true/false` attribution asn-45m's
 * `live-transcript.jsonl` tag events are expected to carry -- that field
 * doesn't exist in asn-45m's landed [com.montauk.voicecapture.tags.TagsEventLine]
 * schema yet (checked against `agent/asn-45m` on 2026-08-06). This holder is
 * the in-memory signal the duck's thought cloud (and its own test suite)
 * consumes today; writing a real persisted event on approval is the
 * fast-follow once asn-45m's schema adds that field -- see this bead's
 * landing report for the exact shape proposed.
 */
object TagApprovalStateHolder {
    private val _approvedKeys = MutableStateFlow<Set<String>>(emptySet())
    val approvedKeys: StateFlow<Set<String>> = _approvedKeys.asStateFlow()

    fun approve(tag: String) {
        _approvedKeys.update { it + normalize(tag) }
    }

    fun isApproved(tag: String): Boolean = normalize(tag) in _approvedKeys.value

    /** Call at the start of every new recording session so a previous session's approvals never leak in. */
    fun reset() {
        _approvedKeys.value = emptySet()
    }

    private fun normalize(tag: String): String = tag.trim().lowercase()
}
