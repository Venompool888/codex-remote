package app.codexremote.android

import org.json.JSONObject

/** Unwrap protocol/provider envelopes without showing transport metadata as chat content. */
internal object AgentErrorMessage {
    fun text(value: Any?): String = unwrap(value, 0) ?: "The task failed. Check the connection or task settings, then try again."
    private fun unwrap(value: Any?, depth: Int): String? {
        if (depth > 5 || value == null || value == JSONObject.NULL) return null
        return when (value) {
            is JSONObject -> unwrap(value.opt("message"), depth + 1) ?: unwrap(value.opt("error"), depth + 1)
            is String -> value.trim().takeIf(String::isNotEmpty)?.let { raw ->
                if (raw.startsWith("{")) {
                    val envelope = runCatching { JSONObject(raw) }.getOrNull()
                    if (envelope != null) unwrap(envelope, depth + 1) else raw
                } else raw
            }
            else -> null
        }
    }
}
