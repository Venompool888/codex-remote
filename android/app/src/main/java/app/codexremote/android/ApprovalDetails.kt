package app.codexremote.android

import org.json.JSONObject
import org.json.JSONArray

internal fun shouldNotifyInteractionExpiry(message: JSONObject): Boolean =
    message.optString("status") == "expired" && message.optString("reason") != "superseded"

internal object ApprovalDetails {
    private fun permissionText(params: JSONObject, field: String): String {
        val names = params.optJSONObject("${field}PathNames") ?: JSONObject()
        val labels = linkedMapOf<String, String>()
        fun label(value: String): String {
            if (!value.startsWith("remote-path://")) return value
            return labels.getOrPut(value) {
                val name = names.optString(value).takeIf { it.isNotBlank() && it.length <= 255 &&
                    it.none { c -> c == '/' || c == '\\' || c < ' ' } } ?: "Restricted location"
                "$name (location ${labels.size + 1})"
            }
        }
        fun project(value: Any?): Any? = when (value) {
            is String -> label(value)
            is JSONArray -> JSONArray().apply { for (index in 0 until value.length()) put(project(value.opt(index))) }
            is JSONObject -> JSONObject().apply { value.keys().forEach { key -> put(label(key), project(value.opt(key))) } }
            else -> value
        }
        return (project(params.optJSONObject(field) ?: JSONObject()) as JSONObject).toString(2)
    }

    fun format(method: String, params: JSONObject): String {
        fun value(key: String) = if (params.isNull(key)) "" else params.optString(key).trim()
        val reason = value("reason")
        if (method == "item/permissions/requestApproval") {
            return listOf(reason, permissionText(params, "permissions"))
                .filter(String::isNotBlank).joinToString("\n\n")
        }
        if (method == "item/commandExecution/requestApproval") {
            val command = value("command")
            val additional = params.optJSONObject("additionalPermissions")?.takeIf { it.length() > 0 }
            val network = params.optJSONObject("networkApprovalContext")
            return listOf(reason, command,
                additional?.let { "Additional permissions requested:\n${permissionText(params, "additionalPermissions")}" }.orEmpty(),
                network?.let { "Network access requested:\n${it.toString(2)}" }.orEmpty())
                .filter(String::isNotBlank).distinct().joinToString("\n\n")
                .ifBlank { "The agent requests permission to run a command." }
        }
        val review = params.optJSONObject("fileChangeReview")
        val patch = if (review?.optString("status") == "available") {
            val changes = review.optJSONArray("changes")
            (0 until (changes?.length() ?: 0)).mapNotNull { index -> changes?.optJSONObject(index) }.joinToString("\n\n") {
                "${it.optString("kind").replaceFirstChar(Char::uppercase)}: ${it.displayPath()}" +
                    it.displayPath("movePath").takeIf(String::isNotBlank)?.let { path -> " → $path" }.orEmpty() +
                    "\n${it.optString("diff")}"
            }
        } else review?.optString("message").orEmpty().ifBlank {
            "File patch details are unavailable. Update the host or review this request there."
        }
        return listOf(patch, reason.ifBlank { "The agent requests permission to change files." },
            params.displayPath("grantRoot").takeIf(String::isNotBlank)?.let {
                "Requested write root: $it\nThis request may grant access for the rest of the session. Review it on the host."
            }.orEmpty()).filter(String::isNotBlank).joinToString("\n\n")
    }
}
