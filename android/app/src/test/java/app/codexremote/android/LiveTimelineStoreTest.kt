package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTimelineStoreTest {
    @Test
    fun lateItemEventsCannotReactivateCompletedItemOrTurn() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        val item = agent("answer", "final_answer", "Done")
        recordCompleted(store, item)
        store.record("item/agentMessage/delta", params(itemId = "answer", delta = "!"))
        assertFalse(store.snapshots(THREAD_ID).getValue(TURN_ID).items.single().active)
        store.record("turn/completed", params(turn = JSONObject().put("id", TURN_ID).put("status", "completed")))
        store.record("item/started", params(item = item))
        assertFalse(store.snapshots(THREAD_ID).getValue(TURN_ID).items.single().active)
        store.record("item/fileChange/patchUpdated", params(itemId = "late-patch")
            .put("changes", JSONArray().put(JSONObject().put("path", "/tmp/late.txt").put("diff", "+late"))))
        assertEquals(1, store.snapshots(THREAD_ID).getValue(TURN_ID).items.size)
        assertFalse(store.snapshots(THREAD_ID).getValue(TURN_ID).items.any {
            it.phase?.lowercase() in setOf("inprogress", "in_progress", "running", "started")
        })
    }

    @Test
    fun interruptErrorAfterActualCompletionCannotRestoreCancelledTurn() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.requestCancellation(THREAD_ID, TURN_ID)
        store.record("turn/completed", params(turn = JSONObject().put("id", TURN_ID).put("status", "completed")))
        store.clearCancellationRequest(THREAD_ID, TURN_ID, restoreRunning = true)
        assertEquals(null, store.activeTurnId(THREAD_ID))
        assertEquals("cancelled", store.snapshots(THREAD_ID).getValue(TURN_ID).status)
    }

    @Test
    fun terminalSnapshotSettlesMissedCompletionWithoutDiscardingLiveItems() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID).put("status", "inProgress")))
        recordCompleted(store, agent("answer", "final_answer", "Done"))
        val snapshot = JSONObject().put("status", JSONObject().put("type", "idle"))
            .put("turns", JSONArray().put(JSONObject().put("id", TURN_ID).put("status", "completed")))

        store.reconcileThreadSnapshot(THREAD_ID, snapshot, 1, 1)

        assertEquals(null, store.activeTurnId(THREAD_ID))
        assertEquals("completed", store.snapshots(THREAD_ID).getValue(TURN_ID).status)
        assertEquals("Done", store.snapshots(THREAD_ID).getValue(TURN_ID).items.single().text)
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID).put("status", "inProgress")))
        assertEquals(null, store.activeTurnId(THREAD_ID))
    }

    @Test
    fun idleSnapshotSettlesOmittedTurnButNewTurnKeepsItsOwnIdentity() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.reconcileThreadSnapshot(THREAD_ID, JSONObject().put("status", JSONObject().put("type", "idle"))
            .put("turns", JSONArray()), 1, 1)
        assertEquals(null, store.activeTurnId(THREAD_ID))

        store.record("turn/started", JSONObject().put("threadId", THREAD_ID)
            .put("turn", JSONObject().put("id", "new-turn")))
        assertEquals("new-turn", store.activeTurnId(THREAD_ID))
    }

    @Test
    fun idleThreadStatusSettlesTurnWhoseRowStillSaysInProgress() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        val snapshot = JSONObject().put("status", JSONObject().put("type", "idle"))
            .put("turns", JSONArray().put(JSONObject().put("id", TURN_ID).put("status", "inProgress")))

        store.reconcileThreadSnapshot(THREAD_ID, snapshot, 1, 1)

        assertEquals(null, store.activeTurnId(THREAD_ID))
        assertEquals("unknown", store.snapshots(THREAD_ID).getValue(TURN_ID).status)
    }

    @Test
    fun idleSnapshotSettlesEveryCachedActiveTurn() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.record("turn/started", JSONObject().put("threadId", THREAD_ID)
            .put("turn", JSONObject().put("id", "second-turn")))

        store.reconcileThreadSnapshot(THREAD_ID,
            JSONObject().put("status", JSONObject().put("type", "idle")).put("turns", JSONArray()), 1, 1)

        assertEquals(null, store.activeTurnId(THREAD_ID))
        assertTrue(store.snapshots(THREAD_ID).values.all { it.status == "unknown" })
    }

    @Test
    fun snapshotPredatingNewTurnCannotClearIt() {
        val store = LiveTimelineStore()
        store.record("turn/started", JSONObject().put("threadId", THREAD_ID)
            .put("turn", JSONObject().put("id", "new-turn")))
        val oldIdleSnapshot = JSONObject().put("status", JSONObject().put("type", "idle"))
            .put("turns", JSONArray())

        assertFalse(store.reconcileThreadSnapshot(THREAD_ID, oldIdleSnapshot, 4, 5))
        assertEquals("new-turn", store.activeTurnId(THREAD_ID))
    }

    @Test
    fun noActiveInterruptResponseSettlesOnlyRequestedTurn() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.requestCancellation(THREAD_ID, TURN_ID)
        store.record("turn/started", JSONObject().put("threadId", THREAD_ID)
            .put("turn", JSONObject().put("id", "new-turn")))
        store.settleActiveTurn(THREAD_ID, TURN_ID)
        assertEquals("unknown", store.snapshots(THREAD_ID).getValue(TURN_ID).status)
        assertEquals("new-turn", store.activeTurnId(THREAD_ID))
    }

    @Test
    fun interruptErrorAfterCompletionCannotResurrectTurn() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.requestCancellation(THREAD_ID, TURN_ID)
        store.record("turn/completed", params(turn = JSONObject().put("id", TURN_ID).put("status", "completed")))
        store.clearCancellationRequest(THREAD_ID, TURN_ID, restoreRunning = false)
        assertEquals(null, store.activeTurnId(THREAD_ID))
    }

    @Test
    fun canonicalLiveItemsRestoreAlternatingTimelineWhenHistoryOmitsTools() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "inProgress")
            .put("items", JSONArray())))
        assertEquals(TURN_ID, store.activeTurnId(THREAD_ID))
        recordCompleted(store, user("live-user", "Inspect it"))
        recordCompleted(store, agent("live-a1", "commentary", "I’ll inspect the configuration."))

        store.record("item/started", params(item = JSONObject()
            .put("id", "live-command")
            .put("type", "commandExecution")
            .put("status", "inProgress")
            .put("command", "rg timeout config")))
        store.record("item/commandExecution/outputDelta", params(itemId = "live-command", delta = "timeout=30"))
        recordCompleted(store, JSONObject()
            .put("id", "live-command")
            .put("type", "commandExecution")
            .put("status", "completed")
            .put("command", "rg timeout config")
            .put("aggregatedOutput", "timeout=30"))

        recordCompleted(store, agent("live-a2", "commentary", "The timeout is 30 seconds; I’ll verify its caller."))
        recordCompleted(store, agent("live-final", "final_answer", "The configuration is valid."))
        store.record("turn/completed", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "completed")
            .put("items", JSONArray())))
        assertEquals(null, store.activeTurnId(THREAD_ID))

        // Mirrors the current legacy app-server replay: messages survive, command items do not.
        val history = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", TURN_ID)
            .put("status", "completed")
            .put("items", JSONArray()
                .put(user("snapshot-user", "Inspect it"))
                .put(agent("snapshot-a1", "commentary", "I’ll inspect the configuration."))
                .put(agent("snapshot-a2", "commentary", "The timeout is 30 seconds; I’ll verify its caller."))
                .put(agent("snapshot-final", "final_answer", "The configuration is valid.")))))

        val items = ThreadProjection.timeline(history, store.snapshots(THREAD_ID))

        assertEquals(
            listOf(
                TimelineItem.Kind.USER,
                TimelineItem.Kind.ACTIVITY_GROUP,
                TimelineItem.Kind.ASSISTANT,
            ),
            items.map { it.kind },
        )
        val execution = items[1]
        assertEquals(
            listOf(TimelineItem.Kind.COMMENTARY, TimelineItem.Kind.COMMAND, TimelineItem.Kind.COMMENTARY),
            execution.children.map { it.kind },
        )
        assertEquals("Ran rg timeout config", execution.children[1].label)
        assertEquals("timeout=30", execution.children[1].text)
        assertFalse(items.any { it.active })
    }

    @Test
    fun liveWebSearchUsesActiveStateThenShowsResultDomain() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "inProgress")
            .put("items", JSONArray())))
        val started = JSONObject()
            .put("id", "web-1")
            .put("type", "webSearch")
            .put("query", "Codex CLI reference")
        store.record("item/started", params(item = started))

        val active = store.snapshots(THREAD_ID).getValue(TURN_ID).items.single()
        assertEquals("Searching the web for Codex CLI reference", active.label)
        assertTrue(active.active)
        assertEquals(TimelineItem.ToolStyle.WEB, active.toolStyle)

        val completed = JSONObject(started.toString()).put("results", JSONArray().put(JSONObject()
            .put("url", "https://developers.openai.com/codex/reference")))
        store.record("item/completed", params(item = completed))

        val done = store.snapshots(THREAD_ID).getValue(TURN_ID).items.single()
        assertEquals("Searched the web for Codex CLI reference | developers.openai.com", done.label)
        assertFalse(done.active)
    }

    @Test
    fun dynamicExecToolUsesTerminalPresentation() {
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "tool-1")
            .put("type", "dynamicToolCall")
            .put("tool", "exec_command")
            .put("status", "completed")
            .put("arguments", JSONObject().put("cmd", "codex --version")))

        assertEquals(TimelineItem.Kind.COMMAND, item?.kind)
        assertEquals("Ran codex --version", item?.label)
        assertEquals("codex --version", item?.rawCommand)
    }

    @Test
    fun shellAndWindowsWrappersAreRemovedFromDisplayLabels() {
        assertEquals("uname -a", ThreadProjection.displayCommand("/bin/bash -lc 'uname -a'"))
        assertEquals("df -h /", ThreadProjection.displayCommand("/usr/bin/env bash -lc \"df -h /\""))
        assertEquals("dir C:\\\\", ThreadProjection.displayCommand("cmd.exe /c dir C:\\\\"))
        assertEquals("Get-ChildItem", ThreadProjection.displayCommand("powershell -Command Get-ChildItem"))
    }

    @Test
    fun runningIntegrationUsesItsActionAndQuery() {
        val item = ThreadProjection.projectItem(JSONObject()
            .put("id", "mcp-1")
            .put("type", "mcpToolCall")
            .put("status", "inProgress")
            .put("tool", "search_code")
            .put("arguments", JSONObject().put("query", "ExecCommandBegin"))
            .put("appContext", JSONObject()
                .put("appName", "GitHub")
                .put("actionName", "Search code")))

        assertEquals("Searching code \"ExecCommandBegin\"", item?.label)
        assertEquals(TimelineItem.ToolStyle.SEARCH, item?.toolStyle)
    }

    @Test
    fun completedLiveToolStopsAnimatingBeforeTurnEnds() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "inProgress")
            .put("items", JSONArray())))
        recordCompleted(store, JSONObject()
            .put("id", "command-1")
            .put("type", "commandExecution")
            .put("status", "completed")
            .put("command", "pwd")
            .put("aggregatedOutput", "/root"))

        val thread = JSONObject().put("turns", JSONArray().put(JSONObject()
            .put("id", TURN_ID)
            .put("status", "inProgress")
            .put("items", JSONArray())))
        val execution = ThreadProjection.timeline(thread, store.snapshots(THREAD_ID)).single()

        assertEquals("Working", execution.label)
        assertEquals("Ran pwd", execution.children.single().label)
        assertTrue(execution.active)
        assertFalse(execution.children.single().active)
    }

    @Test
    fun requestedCancellationSurvivesServerCompletedStatus() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "inProgress")
            .put("items", JSONArray())))
        store.record("item/started", params(item = JSONObject()
            .put("id", "sleep-command")
            .put("type", "commandExecution")
            .put("status", "inProgress")
            .put("command", "sleep 60")))

        store.requestCancellation(THREAD_ID, TURN_ID)
        store.record("turn/completed", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "completed")
            .put("items", JSONArray())))

        val snapshot = store.snapshots(THREAD_ID).getValue(TURN_ID)
        assertEquals("cancelled", snapshot.status)
        val history = JSONObject().put("turns", JSONArray())
        val activity = ThreadProjection.timeline(history, store.snapshots(THREAD_ID)).single()
        assertEquals("Cancelled", activity.label)
        assertEquals("Command cancelled sleep 60", activity.children.single().label)
    }

    @Test
    fun connectionLossFailsTheActiveTurnWithDiagnosticDetail() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "inProgress")
            .put("items", JSONArray())))

        assertTrue(store.failActiveTurn(THREAD_ID, "socket closed"))

        val snapshot = store.snapshots(THREAD_ID).getValue(TURN_ID)
        assertEquals("failed", snapshot.status)
        assertEquals("Connection failed", snapshot.items.single().label)
        assertEquals("socket closed", snapshot.items.single().text)
    }

    @Test
    fun streamedFilePatchStatsSurviveSparseCompletedItem() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject()
            .put("id", TURN_ID)
            .put("status", "inProgress")
            .put("items", JSONArray())))
        store.record("item/started", params(item = JSONObject()
            .put("id", "file-1")
            .put("type", "fileChange")
            .put("status", "inProgress")
            .put("changes", JSONArray())))
        store.record("item/fileChange/patchUpdated", params(itemId = "file-1")
            .put("changes", JSONArray().put(JSONObject()
                .put("path", "/root/test.txt")
                .put("kind", JSONObject().put("type", "update"))
                .put("diff", "@@\n-old\n+new\n+extra"))))
        recordCompleted(store, JSONObject()
            .put("id", "file-1")
            .put("type", "fileChange")
            .put("status", "completed")
            .put("changes", JSONArray().put(JSONObject()
                .put("path", "/root/test.txt")
                .put("kind", JSONObject().put("type", "update"))
                .put("diff", ""))))

        val item = store.snapshots(THREAD_ID).getValue(TURN_ID).items.single()
        assertEquals(1, item.filesChanged)
        assertEquals(2, item.additions)
        assertEquals(1, item.deletions)
        assertEquals("@@\n-old\n+new\n+extra", item.fileChanges.single().patch)
        assertFalse(item.active)
    }

    @Test
    fun structuredPlanUpdateReplacesStepsAndExposesProgress() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.record("turn/plan/updated", params()
            .put("explanation", "Implementation plan")
            .put("plan", JSONArray()
                .put(JSONObject().put("step", "Inspect").put("status", "completed"))
                .put(JSONObject().put("step", "Implement").put("status", "inProgress"))
                .put(JSONObject().put("step", "Verify").put("status", "pending"))))

        val plan = store.snapshots(THREAD_ID).getValue(TURN_ID).items.single()
        assertEquals(listOf("completed", "inProgress", "pending"), plan.planSteps.map { it.status })
        assertEquals(1, plan.stepCurrent)
        assertEquals(3, plan.stepTotal)
        assertTrue(plan.active)
        assertTrue(plan.text.contains("→ Implement"))
    }

    @Test
    fun approvalReviewPreservesStructuredDecisionHistory() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        val started = params()
            .put("reviewId", "review-1")
            .put("startedAtMs", 1_000)
            .put("targetItemId", "command-1")
            .put("review", JSONObject().put("status", "inProgress"))
            .put("action", JSONObject().put("type", "command").put("command", "git status").put("cwd", "/repo"))
        assertTrue(store.record("item/autoApprovalReview/started", started))
        assertTrue(store.snapshots(THREAD_ID).getValue(TURN_ID).items.single().active)

        val completed = JSONObject(started.toString())
            .put("completedAtMs", 1_125)
            .put("decisionSource", "agent")
            .put("review", JSONObject()
                .put("status", "approved")
                .put("riskLevel", "low")
                .put("userAuthorization", "high")
                .put("rationale", "Already authorized"))
        store.record("item/autoApprovalReview/completed", completed)

        val item = store.snapshots(THREAD_ID).getValue(TURN_ID).items.single()
        assertFalse(item.active)
        assertEquals("approved", item.approvalReview?.status)
        assertEquals("command", item.approvalReview?.actionType)
        assertEquals("git status", item.approvalReview?.actionSummary)
        assertEquals(125L, item.durationMs)
    }

    @Test
    fun mcpProgressIsIncrementalAndCompletionKeepsRichReferences() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.record("item/started", params(item = JSONObject()
            .put("id", "mcp-1").put("type", "mcpToolCall")
            .put("server", "media").put("tool", "inspect").put("status", "inProgress")))
        store.record("item/mcpToolCall/progress", params(itemId = "mcp-1").put("message", "Uploading"))
        store.record("item/mcpToolCall/progress", params(itemId = "mcp-1").put("message", "Processing"))
        assertEquals("Uploading\nProcessing", store.snapshots(THREAD_ID).getValue(TURN_ID).items.single().text)

        store.record("item/completed", params(item = JSONObject()
            .put("id", "mcp-1").put("type", "mcpToolCall")
            .put("server", "media").put("tool", "inspect").put("status", "completed")
            .put("result", JSONObject().put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "Done"))
                .put(JSONObject().put("type", "image").put("data", "base64-data").put("mimeType", "image/png"))))))

        val completed = store.snapshots(THREAD_ID).getValue(TURN_ID).items.single()
        assertEquals("Done", completed.text)
        assertEquals(RichOutputReference.Kind.IMAGE, completed.richOutputReferences.single().kind)
        assertEquals("base64-data", completed.richOutputReferences.single().source)
    }

    @Test
    fun hookWithoutTurnUsesOnlyExplicitThreadScope() {
        val store = LiveTimelineStore()
        val hook = JSONObject().put("turnId", JSONObject.NULL).put("run", JSONObject()
            .put("id", "hook-1")
            .put("eventName", "postToolUse")
            .put("status", "completed")
            .put("statusMessage", "Checks passed")
            .put("startedAt", 100)
            .put("durationMs", 20)
            .put("entries", JSONArray()))

        assertFalse(store.record("hook/completed", hook))
        assertTrue(store.record("hook/completed", hook, scopedThreadId = THREAD_ID))
        assertTrue(store.snapshots("other-thread").isEmpty())
        val snapshot = store.snapshots(THREAD_ID).values.single()
        assertEquals("completed", snapshot.status)
        assertEquals("Post Tool Use hook", snapshot.items.single().label)
    }

    @Test
    fun warningsRerouteAndTurnDiffStayScopedToTheirThread() {
        val store = LiveTimelineStore()
        store.record("turn/started", params(turn = JSONObject().put("id", TURN_ID)))
        store.record("warning", JSONObject().put("threadId", THREAD_ID).put("message", "Heads up"))
        store.record("model/rerouted", params().put("fromModel", "a").put("toModel", "b")
            .put("reason", "highRiskCyberActivity"))
        store.record("turn/diff/updated", params().put("diff",
            "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-old\n+new\n"))

        val snapshot = store.snapshots(THREAD_ID).getValue(TURN_ID)
        assertTrue(snapshot.items.any { it.label == "Warning" })
        assertTrue(snapshot.items.any { it.label == "Model rerouted" })
        assertEquals(setOf("a.kt"), snapshot.turnDiff?.changedFiles)
        assertTrue(store.snapshots("other-thread").isEmpty())
    }

    private fun recordCompleted(store: LiveTimelineStore, item: JSONObject) {
        store.record("item/started", params(item = JSONObject(item.toString())))
        store.record("item/completed", params(item = item))
    }

    private fun params(
        item: JSONObject? = null,
        turn: JSONObject? = null,
        itemId: String? = null,
        delta: String? = null,
    ) = JSONObject()
        .put("threadId", THREAD_ID)
        .put("turnId", TURN_ID)
        .apply {
            item?.let { put("item", it) }
            turn?.let { put("turn", it) }
            itemId?.let { put("itemId", it) }
            delta?.let { put("delta", it) }
        }

    private fun user(id: String, text: String) = JSONObject()
        .put("id", id)
        .put("type", "userMessage")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun agent(id: String, phase: String, text: String) = JSONObject()
        .put("id", id)
        .put("type", "agentMessage")
        .put("phase", phase)
        .put("text", text)

    private companion object {
        const val THREAD_ID = "thread-live"
        const val TURN_ID = "turn-live"
    }
}
