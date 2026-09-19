package app.codexremote.android.presentation.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class WorkspaceMutationControllerTest {
    private data class Call(
        val serverId: String,
        val method: String,
        val params: JSONObject,
        val done: (JSONObject?, String?) -> Unit,
    )

    private val calls = mutableListOf<Call>()
    private fun controller() = WorkspaceMutationController { server, method, params, done ->
        calls += Call(server, method, params, done)
    }
    private val scope = WorkspaceMutationScope("server-a", "device-a", "remote-workspace://workspace-a", "thread-a")

    @Test fun textLoadProvidesHashAndSaveRunsOnlyAfterExactPreviewConfirmation() {
        val controller = controller()
        controller.setScope(scope, setOf(WorkspaceMutationController.READ_TEXT, WorkspaceMutationController.SAVE_TEXT))
        controller.loadText("notes/today.txt")
        val read = calls.removeAt(0)
        assertEquals("server-a", read.serverId)
        assertEquals(scope.cwd, read.params.getString("cwd"))
        read.done(JSONObject().put("path", "notes/today.txt").put("text", "first").put("size", 5), null)

        val document = controller.uiState.value.document!!
        assertEquals(hash("first"), document.sha256)
        controller.previewSaveText("second")
        assertTrue(calls.isEmpty())
        val preview = controller.uiState.value.preview!!
        assertEquals("notes/today.txt", preview.subject)
        assertTrue(preview.consequence.contains("has not changed"))
        controller.confirmAction(preview.id)

        val save = calls.single()
        assertEquals(WorkspaceMutationController.SAVE_TEXT, save.method)
        assertEquals(hash("first"), save.params.getString("expectedSha256"))
        assertEquals("save_workspace_text", save.params.getJSONObject("confirmation").getString("action"))
        assertEquals("notes/today.txt", save.params.getJSONObject("confirmation").getString("subject"))
        save.done(JSONObject().put("path", "notes/today.txt").put("size", 6).put("sha256", hash("second")), null)
        assertEquals("second", controller.uiState.value.document?.text)
        assertEquals(hash("second"), controller.uiState.value.document?.sha256)
    }

    @Test fun createNormalizesRelativePathAndNeverExecutesDuringPreview() {
        val controller = controller()
        controller.setScope(scope, setOf(WorkspaceMutationController.CREATE_TEXT))
        controller.previewCreateText("notes/drafts/../today.txt", "hello")
        assertTrue(calls.isEmpty())
        val preview = controller.uiState.value.preview!!
        assertEquals("notes/today.txt", preview.subject)
        controller.confirmAction(preview.id)
        val call = calls.single()
        assertEquals("notes/today.txt", call.params.getString("path"))
        assertEquals("create_workspace_text", call.params.getJSONObject("confirmation").getString("action"))

        val oversized = "😀".repeat(131_073)
        assertThrows(IllegalArgumentException::class.java) { controller.previewCreateText("large.txt", oversized) }
        assertThrows(IllegalArgumentException::class.java) { controller.previewCreateText("../../outside.txt", "no") }
    }

    @Test fun staleReadAndMutationResultsCannotCrossScopeGeneration() {
        val controller = controller()
        controller.setScope(scope, setOf(WorkspaceMutationController.READ_TEXT, WorkspaceMutationController.CREATE_TEXT))
        controller.loadText("old.txt")
        val oldRead = calls.removeAt(0)
        controller.previewCreateText("new.txt", "new")
        val preview = controller.uiState.value.preview!!
        controller.confirmAction(preview.id)
        val oldWrite = calls.removeAt(0)

        val next = WorkspaceMutationScope("server-b", "device-b", "remote-workspace://workspace-b", "thread-b")
        controller.setScope(next, setOf(WorkspaceMutationController.READ_TEXT, WorkspaceMutationController.CREATE_TEXT, "host/arbitrary"))
        oldRead.done(JSONObject().put("path", "old.txt").put("text", "old").put("size", 3), null)
        oldWrite.done(JSONObject().put("path", "new.txt").put("size", 3).put("sha256", hash("new")), null)

        assertEquals("server-b", controller.uiState.value.scope?.serverId)
        assertNull(controller.uiState.value.document)
        assertEquals(setOf(WorkspaceMutationController.READ_TEXT, WorkspaceMutationController.CREATE_TEXT),
            controller.uiState.value.supportedMethods)
    }

    @Test fun backgroundTerminalActionsAreTypedAndTaskScoped() {
        val controller = controller()
        controller.setScope(scope, setOf(
            WorkspaceMutationController.LIST_TERMINALS,
            WorkspaceMutationController.TERMINATE_TERMINAL,
            WorkspaceMutationController.CLEAN_TERMINALS,
        ))
        controller.refreshBackgroundTerminals(limit = 10, cursor = "opaque cursor")
        val list = calls.removeAt(0)
        assertEquals("thread-a", list.params.getString("threadId"))
        list.done(JSONObject().put("data", JSONArray().put(JSONObject()
            .put("itemId", "item-1").put("processId", "process-1").put("command", "npm test")
            .put("cwdName", "project").put("osPid", 42).put("cpuPercent", 1.5).put("rssKb", 2048)
            .put("secret", "ignored"))).put("nextCursor", "next cursor"), null)
        assertEquals(BackgroundTerminalState("item-1", "process-1", "npm test", "project", 42, 1.5, 2048),
            controller.uiState.value.backgroundTerminals.single())

        controller.previewTerminateBackgroundTerminal("process-1")
        assertTrue(calls.isEmpty())
        controller.confirmAction(controller.uiState.value.preview!!.id)
        val terminate = calls.removeAt(0)
        assertEquals("terminate_background_terminal", terminate.params.getJSONObject("confirmation").getString("action"))
        assertEquals("thread-a:process-1", terminate.params.getJSONObject("confirmation").getString("subject"))
        terminate.done(JSONObject().put("terminated", true), null)
        assertTrue(controller.uiState.value.backgroundTerminals.isEmpty())

        controller.previewCleanBackgroundTerminals()
        controller.confirmAction(controller.uiState.value.preview!!.id)
        assertEquals("clean_background_terminals", calls.single().params.getJSONObject("confirmation").getString("action"))
    }

    @Test fun memoryResetUsesFixedConfirmationAndCapabilityGate() {
        val controller = controller()
        controller.setScope(scope, setOf(WorkspaceMutationController.RESET_MEMORY))
        controller.previewResetMemory()
        assertTrue(calls.isEmpty())
        controller.confirmAction(controller.uiState.value.preview!!.id)
        val reset = calls.single()
        assertEquals(WorkspaceMutationController.RESET_MEMORY, reset.method)
        assertEquals("reset_memory", reset.params.getJSONObject("confirmation").getString("action"))
        assertEquals("Codex memory", reset.params.getJSONObject("confirmation").getString("subject"))
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
