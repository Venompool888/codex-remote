package app.codexremote.android

import app.codexremote.android.presentation.resources.*
import app.codexremote.android.presentation.workspace.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResourceControllerTest {
    @Test fun olderFileReadCannotReplaceNewSelectionOrHost() {
        val replies = mutableListOf<(JSONObject?, String?) -> Unit>()
        val controller = RichResourcesController { _, _, done -> replies += done }
        val methods = setOf("host/file/readReference")
        controller.setScope(RichResourceScope("a", "device", "task"), methods)
        controller.readFile("remote-file://first"); controller.readFile("remote-file://second")
        replies[0](JSONObject().put("path", "first"), null)
        assertNull(controller.uiState.value.file)
        replies[1](JSONObject().put("path", "second").put("text", "content").put("startLine", 12), null)
        assertEquals(12, controller.uiState.value.file?.startLine)
        controller.readFile("remote-file://third")
        controller.setScope(RichResourceScope("b", "device", "task"), methods)
        replies[2](JSONObject().put("path", "third"), null)
        assertNull(controller.uiState.value.file)
    }
    @Test fun denialPreviewDoesNotRunAndConfirmationCannotSendAssessment() {
        val calls = mutableListOf<JSONObject>()
        val controller = RichResourcesController { _, params, _ -> calls += params }
        controller.setScope(RichResourceScope("a", "d", "t"), setOf("host/guardian/approveDenied"))
        controller.consumeEvent(JSONObject("""{"guardianDenied":{"reviewId":"r","threadId":"t","approvable":true,"actionSummary":"fixture"}}"""))
        controller.previewApproval("r")
        assertTrue(calls.isEmpty())
        controller.confirmApproval(); controller.confirmApproval()
        assertEquals(1, calls.size)
        assertEquals("r", calls.single().getString("reviewId"))
        assertFalse(calls.single().has("event"))
    }
    @Test fun clearedSearchRejectsPriorResults() {
        var reply: ((JSONObject?, String?) -> Unit)? = null
        val controller = WorkspaceToolsController({ _, _, done -> reply = done })
        controller.setScope(WorkspaceToolsScope("a", "d", "workspace"), setOf("host/workspace/files/search"))
        controller.search("source")
        val stale = reply!!
        controller.search("")
        stale(JSONObject("""{"files":[{"path":"old"}]}"""), null)
        assertTrue(controller.uiState.value.files.isEmpty())
        assertFalse(controller.uiState.value.searching)
    }
}
