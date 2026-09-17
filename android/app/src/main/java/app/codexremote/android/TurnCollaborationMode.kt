package app.codexremote.android

import org.json.JSONObject

internal object TurnCollaborationMode {
    fun apply(params: JSONObject, plan: Boolean, model: String?, effort: String?) {
        if (model == null) return
        // Omission preserves the remote session mode, including after an Android cold start.
        params.put("collaborationMode", JSONObject().put("mode", if (plan) "plan" else "default")
            .put("settings", JSONObject().put("model", model)
                .put("reasoning_effort", effort).put("developer_instructions", JSONObject.NULL)))
    }
}
