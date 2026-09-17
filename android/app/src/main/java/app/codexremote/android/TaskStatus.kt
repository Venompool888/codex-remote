package app.codexremote.android

import org.json.JSONObject

/** Only actionable transitions produce alerts; streamed content never does. */
object TaskStatus {
    fun label(message: JSONObject): String? {
        val params = message.optJSONObject("params") ?: return null
        val method = message.optString("method")
        val status = params.optJSONObject("turn")?.optString("status")
        return when {
            message.optString("type") == "codex_request" && method.contains("Approval") -> "Waiting for approval"
            message.optString("type") == "codex_request" -> "Waiting for your input"
            method == "turn/failed" || (method == "turn/completed" && status == "failed") -> "Task failed"
            method == "turn/completed" && status !in setOf("cancelled", "interrupted") -> "Task completed"
            method == "error" && !params.optBoolean("willRetry") -> "Task failed"
            else -> null
        }
    }
}
