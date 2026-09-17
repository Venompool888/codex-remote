package app.codexremote.android

import org.json.JSONObject

internal data class ApprovalChoices(val allow: Boolean, val negativeDecision: String?) {
    val negativeLabel get() = when (negativeDecision) { "decline" -> "Deny"; "cancel" -> "Cancel task"; else -> "Cancel request" }
    companion object {
        fun from(params: JSONObject): ApprovalChoices {
            // Missing/null means the host uses the legacy decision set; an explicit empty list does not.
            if (!params.has("availableDecisions") || params.isNull("availableDecisions")) return ApprovalChoices(true, "decline")
            val values = params.optJSONArray("availableDecisions") ?: return ApprovalChoices(false, null)
            val choices = (0 until values.length()).mapNotNull { values.opt(it) as? String }.toSet()
            return ApprovalChoices("accept" in choices, when {
                "decline" in choices -> "decline"
                "cancel" in choices -> "cancel"
                else -> null
            })
        }
    }
}
