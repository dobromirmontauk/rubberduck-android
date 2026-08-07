package com.montauk.voicecapture.logging

/**
 * Pure line formatter for [RubberduckLog] (bead asn-jht): one grep-able
 * `key=value` line per event, shared verbatim between logcat and the rolling
 * file sink so `adb logcat -s rubberduck` and a pulled log file read
 * identically. A value containing whitespace is double-quoted so a naive
 * whitespace split (`awk`, `cut -d' '`) still sees one token per field;
 * nothing else is escaped -- callers must never pass transcript/audio
 * content through this (see [RubberduckLog]'s own KDoc for the exact
 * contract).
 */
object LogFormatter {
    fun format(nowMs: Long, component: String, event: String, fields: List<Pair<String, Any?>>): String {
        val prefix = "ts=$nowMs component=$component event=$event"
        if (fields.isEmpty()) return prefix
        val rest = fields.joinToString(" ") { (key, value) -> "$key=${formatValue(value)}" }
        return "$prefix $rest"
    }

    private fun formatValue(value: Any?): String {
        val text = value?.toString() ?: "null"
        return if (text.any { it.isWhitespace() }) "\"$text\"" else text
    }
}
