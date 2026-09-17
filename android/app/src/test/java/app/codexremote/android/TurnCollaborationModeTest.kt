package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnCollaborationModeTest {
    @Test fun ordinaryTurnExplicitlyExitsPreviouslySelectedRemotePlanMode() {
        var remoteMode = "default"
        for (plan in listOf(true, false, true, false)) {
            val params = JSONObject()
            TurnCollaborationMode.apply(params, plan, "model-from-host", "medium")
            // App Server preserves its prior mode when the field is omitted.
            params.optJSONObject("collaborationMode")?.let { mode ->
                remoteMode = mode.getString("mode")
                assertEquals("model-from-host", mode.getJSONObject("settings").getString("model"))
                assertTrue(mode.getJSONObject("settings").isNull("developer_instructions"))
            }
            assertEquals(if (plan) "plan" else "default", remoteMode)
        }
    }
}
