package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskStatusTest {
    private fun event(method: String, params: JSONObject = JSONObject(), type: String = "codex_event") =
        JSONObject().put("type", type).put("method", method).put("params", params)
    @Test fun alertsOnlyImportantTransitions() {
        assertNull(TaskStatus.label(event("item/agentMessage/delta", JSONObject().put("delta", "private text"))))
        assertNull(TaskStatus.label(event("error", JSONObject().put("willRetry", true))))
        assertEquals("Task failed", TaskStatus.label(event("turn/failed")))
        assertEquals("Task failed", TaskStatus.label(event("turn/completed", JSONObject().put("turn", JSONObject().put("status", "failed")))))
        assertEquals("Task completed", TaskStatus.label(event("turn/completed")))
        assertNull(TaskStatus.label(event("turn/completed", JSONObject().put("turn", JSONObject().put("status", "interrupted")))))
        assertEquals("Waiting for your input", TaskStatus.label(event("item/tool/requestUserInput", type = "codex_request")))
        assertEquals("Waiting for approval", TaskStatus.label(event("item/fileChange/requestApproval", type = "codex_request")))
    }
}
