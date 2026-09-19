package app.codexremote.android

import app.codexremote.android.presentation.conversation.SubagentViewerController
import app.codexremote.android.presentation.conversation.ConversationController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentViewerTest {
    @Test fun protocolRetainsTargetsAndDoesNotConfuseToolCompletionWithAgentCompletion() {
        val item = ThreadProjection.projectItem(JSONObject("""{
          "id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed",
          "senderThreadId":"parent","receiverThreadIds":["child"],"model":"example-model",
          "agentsStates":{"child":{"status":"running","message":null}}
        }"""))!!
        assertEquals("completed", item.phase)
        assertEquals("running", item.subagents.single().status)
        assertEquals("child", item.subagents.single().threadId)
        assertEquals("", item.subagents.single().message)
        val activity = ThreadProjection.projectItem(JSONObject("""{
          "id":"activity","type":"subAgentActivity","agentThreadId":"child","agentPath":"reviewer"
        }"""))!!
        val refs = SubagentReference.collect(listOf(TimelineItem("group", "", "", TimelineItem.Kind.ACTIVITY_GROUP,
            children = listOf(item, activity))))
        assertEquals(1, refs.size)
        assertEquals("reviewer", refs.single().name)
        assertEquals("running", refs.single().status)
        assertEquals("example-model", refs.single().model)
    }

    @Test fun historyAndLiveTimelineBothRetainChildLinks() {
        val raw = JSONObject("""{
          "id":"spawn","type":"collabAgentToolCall","tool":"spawnAgent","status":"completed",
          "receiverThreadIds":["child"],"agentsStates":{"child":{"status":"running"}}
        }""")
        val history = JSONObject().put("id", "parent").put("turns", org.json.JSONArray().put(
            JSONObject().put("id", "turn").put("status", "inProgress").put("items", org.json.JSONArray().put(raw))))
        assertEquals("child", SubagentReference.collect(ThreadProjection.timeline(history)).single().threadId)
        val empty = JSONObject().put("id", "parent").put("turns", org.json.JSONArray())
        val live = mapOf("turn" to LiveTurnSnapshot("inProgress", listOf(ThreadProjection.projectItem(raw)!!)))
        assertEquals("child", SubagentReference.collect(ThreadProjection.timeline(empty, live)).single().threadId)
    }

    @Test fun stateOnlyTargetsAndMissingLegacyMetadataAreSafe() {
        val refs = SubagentReference.fromItem(JSONObject("""{
          "senderThreadId":"parent","receiverThreadIds":[null,"",7,"parent"],
          "agentsStates":{"child":{"status":"errored","message":"Failed"}}
        }"""))
        assertEquals(listOf("child"), refs.map { it.threadId })
        assertEquals("errored", refs.single().status)
        assertTrue(SubagentReference.fromItem(JSONObject()).isEmpty())
    }

    @Test fun closedAndReplacedInspectorRejectLateResultsAndCoalesceRefresh() {
        val requests = mutableListOf<Pair<String, (JSONObject?, String?) -> Unit>>()
        val controller = SubagentViewerController { id, callback -> requests += id to callback }
        controller.open(SubagentReference("first"))
        controller.refresh()
        assertEquals(1, requests.size)
        controller.open(SubagentReference("second"))
        requests[0].second(thread("first", "Stale"), null)
        assertEquals("second", controller.uiState.value.agent?.threadId)
        assertTrue(controller.uiState.value.loading)
        requests[1].second(thread("second", "Current"), null)
        assertEquals("Current", controller.uiState.value.items.single().text)
        controller.refresh()
        controller.close()
        requests.last().second(thread("second", "After close"), null)
        assertNull(controller.uiState.value.agent)
        assertTrue(controller.uiState.value.items.isEmpty())
    }

    @Test fun wrongThreadAndReadFailureCannotPopulateInspectorAndCanRetry() {
        var callback: ((JSONObject?, String?) -> Unit)? = null
        val controller = SubagentViewerController { _, done -> callback = done }
        controller.open(SubagentReference("child"))
        callback!!(thread("other", "Wrong"), null)
        assertNotNull(controller.uiState.value.error)
        assertTrue(controller.uiState.value.items.isEmpty())
        controller.refresh()
        callback!!(null, "sensitive internal error")
        assertFalse(controller.uiState.value.error!!.contains("sensitive"))
        controller.refresh()
        callback!!(thread("child", "Recovered"), null)
        assertNull(controller.uiState.value.error)
        assertEquals("Recovered", controller.uiState.value.items.single().text)
    }

    @Test fun navigationAndDisconnectCloseTheInspector() {
        val viewer = SubagentViewerController { _, _ -> }
        val conversation = ConversationController(subagents = viewer)
        conversation.updateThread("parent", "", "", "", "server-a", true)
        viewer.open(SubagentReference("child"))
        conversation.updateThread("other", "", "", "", "server-a", true)
        assertNull(viewer.uiState.value.agent)
        viewer.open(SubagentReference("child"))
        conversation.updateThread("other", "", "", "", "server-b", true)
        assertNull(viewer.uiState.value.agent)
        viewer.open(SubagentReference("child"))
        conversation.updateThread("other", "", "", "", "server-b", false)
        assertNull(viewer.uiState.value.agent)
    }

    @Test fun directControlRequiresExplicitHostFlagAndLiveEventsStayScoped() {
        var selected: String? = null
        var reply: ((JSONObject?, String?) -> Unit)? = null
        val viewer = SubagentViewerController(onOpenAsTask = { selected = it }) { _, done -> reply = done }
        viewer.open(SubagentReference("child"))
        reply!!(thread("child", "History"), null)
        viewer.openAsTask()
        assertNull(selected)
        assertFalse(viewer.consumeEvent("thread/status/changed", JSONObject("""{"threadId":"other","status":{"type":"active"}}""")))
        viewer.refresh()
        reply!!(thread("child", "History").put("canAcceptDirectInput", true), null)
        viewer.openAsTask()
        assertEquals("child", selected)
        assertTrue(viewer.consumeEvent("thread/status/changed", JSONObject("""{"threadId":"child","status":{"type":"active"}}""")))
        assertEquals("active", viewer.uiState.value.agent?.status)
        viewer.close()
        assertFalse(viewer.consumeEvent("thread/status/changed", JSONObject("""{"threadId":"child","status":{"type":"active"}}""")))
    }

    private fun thread(id: String, text: String) = JSONObject("""{
       "id":"$id","status":{"type":"idle"},"turns":[{"id":"turn","status":"completed",
       "items":[{"id":"message","type":"agentMessage","text":"$text"}]}]
    }""")
}
