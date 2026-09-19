package app.codexremote.android.presentation.tasktools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskToolsControllerTest {
    private data class Call(val method: String, val params: JSONObject, val done: (JSONObject?, String?) -> Unit)
    private val calls = mutableListOf<Call>()
    private val selected = mutableListOf<String>()
    private val changed = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val inspected = mutableListOf<String>()
    private fun controller() = TaskToolsController(
        rpc = { method, params, callback -> calls += Call(method, params, callback) },
        onTaskSelected = selected::add,
        onTaskChanged = changed::add,
        onTaskDeleted = deleted::add,
        onInspectDescendant = inspected::add,
    )
    private val scope = TaskToolsScope("server-a", "device-a", "task-a", "Original")

    @Test fun availabilityComesOnlyFromAdvertisedMethods() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/fork", "thread/searchOccurrences", "thread/goal/get"))

        val available = controller.uiState.value.availability
        assertTrue(available.fork.writable)
        assertTrue(available.search.readable)
        assertTrue(available.goal.readable)
        assertFalse(available.goal.writable)
        assertFalse(available.rename.writable)
        assertNotNull(available.rename.unavailableReason)

        controller.renameTask("New")
        assertTrue(calls.isEmpty())
        assertEquals("This action is unavailable on this host.", controller.uiState.value.action.error)
    }

    @Test fun switchingScopeRejectsLatePrivateResults() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/goal/get"))
        controller.refreshGoal()
        assertEquals("task-a", calls.single().params.getString("threadId"))

        controller.setScope(TaskToolsScope("server-b", "device-b", "task-b"), setOf("thread/goal/get"))
        calls.single().done(JSONObject().put("goal", goal("Private A")), null)

        assertEquals("task-b", controller.uiState.value.scope?.taskId)
        assertNull(controller.uiState.value.goal.goal)
    }

    @Test fun displayOnlyScopeUpdatesPreserveLoadedStateAndPendingRequests() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/goal/get", "thread/unarchive"))
        controller.refreshGoal()
        controller.setScope(scope.copy(taskName = "Renamed elsewhere", archived = true), setOf("thread/goal/get", "thread/unarchive"))
        calls.single().done(JSONObject().put("goal", goal("Still current")), null)

        assertEquals("Renamed elsewhere", controller.uiState.value.scope?.taskName)
        assertTrue(controller.uiState.value.scope!!.archived)
        assertEquals("Still current", controller.uiState.value.goal.goal?.objective)
        assertTrue(controller.uiState.value.availability.archive.writable)
    }

    @Test fun renameAndForkUseTypedTaskActions() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/name/set", "thread/fork"))
        controller.renameTask("  New name  ")
        assertEquals("thread/name/set", calls.single().method)
        assertEquals("task-a", calls.single().params.getString("threadId"))
        assertEquals("New name", calls.single().params.getString("name"))
        calls.removeAt(0).done(JSONObject(), null)
        assertEquals("New name", controller.uiState.value.scope?.taskName)
        assertEquals(listOf("task-a"), changed)

        controller.forkTask()
        assertEquals("thread/fork", calls.single().method)
        calls.single().done(JSONObject().put("thread", JSONObject().put("id", "task-child")), null)
        assertEquals(listOf("task-a", "task-child"), changed)
        assertEquals(listOf("task-child"), selected)
    }

    @Test fun archiveChangesAvailableRestoreActionAndDeleteNotifiesRuntime() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/archive", "thread/unarchive", "thread/delete"))
        controller.archiveTask()
        calls.removeAt(0).done(JSONObject(), null)
        assertTrue(controller.uiState.value.scope!!.archived)
        assertTrue(controller.uiState.value.availability.archive.writable)

        controller.unarchiveTask()
        calls.removeAt(0).done(JSONObject(), null)
        assertFalse(controller.uiState.value.scope!!.archived)

        controller.deleteTask()
        calls.single().done(JSONObject(), null)
        assertEquals(listOf("task-a"), deleted)
    }

    @Test fun searchCombinesTaskSnippetsAndCurrentTaskOccurrences() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/search", "thread/searchOccurrences"))
        controller.search("needle")
        assertEquals(setOf("thread/search", "thread/searchOccurrences"), calls.map { it.method }.toSet())
        assertTrue(calls.all { it.params.getString("searchTerm") == "needle" })
        assertEquals("task-a", calls.first { it.method == "thread/searchOccurrences" }.params.getString("threadId"))

        calls.first { it.method == "thread/search" }.done(JSONObject()
            .put("data", JSONArray().put(JSONObject()
                .put("thread", JSONObject().put("id", "task-old").put("name", "Old task"))
                .put("snippet", "a needle here")))
            .put("nextCursor", "tasks-next"), null)
        calls.first { it.method == "thread/searchOccurrences" }.done(JSONObject()
            .put("data", JSONArray().put(JSONObject()
                .put("turnId", "turn-1").put("itemId", "item-1").put("snippet", "needle")
                .put("snippetMatchRange", JSONObject().put("start", 0).put("end", 6)).put("turnCursor", "turn-cursor")))
            .put("nextCursor", "occ-next"), null)

        val state = controller.uiState.value.search
        assertEquals("task-old", state.matches.single().threadId)
        assertEquals("item-1", state.occurrences.single().itemId)
        assertEquals("tasks-next", state.nextCursor)
        assertEquals("occ-next", state.nextOccurrenceCursor)
        assertFalse(state.loading)
    }

    @Test fun goalSetPreservesNullableBudgetAndParsesUsage() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/goal/set"))
        controller.setGoal("Ship it", GoalStatus.ACTIVE, null)
        val call = calls.single()
        assertEquals("thread/goal/set", call.method)
        assertTrue(call.params.isNull("tokenBudget"))
        call.done(JSONObject().put("goal", goal("Ship it")), null)
        assertEquals(900L, controller.uiState.value.goal.goal?.tokensUsed)
    }

    @Test fun accountWrapperKeepsSuccessfulSectionWhenOtherSectionIsUnavailable() {
        val controller = controller()
        controller.setScope(scope, setOf("host/account/usage"))
        controller.refreshAccountUsage()
        val call = calls.single()
        assertEquals("host/account/usage", call.method)
        call.done(JSONObject()
            .put("rateLimits", JSONObject().put("supported", true).put("data", JSONObject()
                .put("rateLimits", JSONObject().put("limitId", "codex").put("limitName", "Codex")
                    .put("primary", JSONObject().put("usedPercent", 42.5).put("windowDurationMins", 300).put("resetsAt", 99)))))
            .put("tokenUsage", JSONObject().put("supported", false).put("error", "Upgrade host Codex")), null)

        val state = controller.uiState.value.account
        assertEquals(42.5, state.rateLimits.single().primary!!.usedPercent, 0.0)
        assertNull(state.usage)
        assertEquals("Upgrade host Codex", state.error)
        assertFalse(state.loading)
    }

    @Test fun accountUsageAcceptsSerializedBigInts() {
        val controller = controller()
        controller.setScope(scope, setOf("host/account/usage"))
        controller.refreshAccountUsage()
        calls.single().done(JSONObject()
            .put("rateLimits", JSONObject().put("supported", false).put("error", "No rate data"))
            .put("tokenUsage", JSONObject().put("supported", true).put("data", JSONObject()
                .put("summary", JSONObject().put("lifetimeTokens", "1234567890123"))
                .put("dailyUsageBuckets", JSONArray().put(JSONObject().put("startDate", "2026-09-19").put("tokens", "45"))))), null)
        assertEquals(1_234_567_890_123L, controller.uiState.value.account.usage?.lifetimeTokens)
        assertEquals(45L, controller.uiState.value.account.usage?.daily?.single()?.tokens)
    }

    @Test fun scopedEventsUpdateGoalAndMergeSparseRateLimits() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/goal/get", "host/account/usage"))
        assertFalse(controller.consumeEvent("thread/goal/updated", JSONObject().put("threadId", "other").put("goal", goal("Wrong"))))
        assertTrue(controller.consumeEvent("thread/goal/updated", JSONObject().put("threadId", "task-a").put("goal", goal("Live"))))
        assertEquals("Live", controller.uiState.value.goal.goal?.objective)

        controller.refreshAccountUsage()
        calls.single().done(JSONObject()
            .put("rateLimits", JSONObject().put("supported", true).put("data", JSONObject()
                .put("rateLimits", JSONObject().put("limitId", "codex").put("limitName", "Codex plan")
                    .put("primary", JSONObject().put("usedPercent", 20).put("resetsAt", 100)))))
            .put("tokenUsage", JSONObject().put("supported", false).put("error", "Unavailable")), null)
        assertTrue(controller.consumeEvent("account/rateLimits/updated", JSONObject().put("rateLimits", JSONObject()
            .put("limitId", "codex").put("primary", JSONObject().put("usedPercent", 70).put("resetsAt", 200)))))
        val limit = controller.uiState.value.account.rateLimits.single()
        assertEquals("Codex plan", limit.name)
        assertEquals(70.0, limit.primary!!.usedPercent, 0.0)

        assertTrue(controller.consumeEvent("thread/goal/cleared", JSONObject().put("threadId", "task-a")))
        assertNull(controller.uiState.value.goal.goal)
    }

    @Test fun goalAndRateLimitEventsInvalidateOlderRefreshes() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/goal/get", "host/account/usage"))
        controller.refreshGoal()
        val oldGoal = calls.removeAt(0)
        assertTrue(controller.consumeEvent("thread/goal/updated", JSONObject()
            .put("threadId", "task-a").put("goal", goal("Event goal"))))
        oldGoal.done(JSONObject().put("goal", goal("Stale goal")), null)
        assertEquals("Event goal", controller.uiState.value.goal.goal?.objective)

        controller.refreshAccountUsage()
        val oldUsage = calls.removeAt(0)
        assertTrue(controller.consumeEvent("account/rateLimits/updated", JSONObject().put("rateLimits", JSONObject()
            .put("limitId", "codex").put("primary", JSONObject().put("usedPercent", 75)))))
        oldUsage.done(JSONObject()
            .put("rateLimits", JSONObject().put("supported", true).put("data", JSONObject()
                .put("rateLimits", JSONObject().put("limitId", "codex")
                    .put("primary", JSONObject().put("usedPercent", 10)))))
            .put("tokenUsage", JSONObject().put("supported", false).put("error", "Unavailable")), null)
        assertEquals(75.0, controller.uiState.value.account.rateLimits.single().primary!!.usedPercent, 0.0)
        assertFalse(controller.uiState.value.account.loading)
    }

    @Test fun reviewSupportsDetachedTypedTarget() {
        val controller = controller()
        controller.setScope(scope, setOf("review/start"))
        controller.startReview(ReviewTarget.BaseBranch("main"), ReviewDelivery.DETACHED)
        val call = calls.single()
        assertEquals("review/start", call.method)
        assertEquals("baseBranch", call.params.getJSONObject("target").getString("type"))
        assertEquals("main", call.params.getJSONObject("target").getString("branch"))
        assertEquals("detached", call.params.getString("delivery"))
        call.done(JSONObject().put("reviewThreadId", "review-task"), null)
        assertEquals("review-task", controller.uiState.value.review.reviewThreadId)
        assertEquals(listOf("review-task"), changed)
    }

    @Test fun historyAndDescendantsPageWithoutRawJsonState() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/turns/list", "thread/list"))
        controller.refreshHistory()
        assertEquals("summary", calls.single().params.getString("itemsView"))
        calls.removeAt(0).done(JSONObject().put("data", JSONArray().put(JSONObject()
            .put("id", "turn-1").put("status", "completed")
            .put("items", JSONArray().put(JSONObject().put("id", "item-1").put("type", "agentMessage").put("text", "Finished work")))))
            .put("nextCursor", "older"), null)
        assertEquals("Agent Message", controller.uiState.value.history.entries.single().title)
        assertEquals("Finished work", controller.uiState.value.history.entries.single().summary)
        assertEquals("older", controller.uiState.value.history.nextCursor)

        controller.refreshDescendants()
        val descendantCall = calls.removeAt(0)
        assertEquals("task-a", descendantCall.params.getString("ancestorThreadId"))
        descendantCall.done(JSONObject().put("data", JSONArray().put(JSONObject()
            .put("id", "child-a").put("agentNickname", "Agent A").put("canAcceptDirectInput", false)
            .put("source", JSONObject().put("subAgent", JSONObject().put("thread_spawn", JSONObject().put("parent_thread_id", "task-a"))))
            .put("status", JSONObject().put("type", "running")))), null)
        assertEquals("Agent A", controller.uiState.value.descendants.tasks.single().title)
        assertEquals("task-a", controller.uiState.value.descendants.tasks.single().parentThreadId)
        assertEquals(false, controller.uiState.value.descendants.tasks.single().canAcceptDirectInput)
        controller.selectDescendant("child-a")
        assertTrue(selected.isEmpty())
        assertEquals(listOf("child-a"), inspected)
    }

    @Test fun historySummariesReadUserContentAndReasoningArrays() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/turns/list"))
        controller.refreshHistory()
        val items = JSONArray()
            .put(JSONObject().put("id", "user-1").put("type", "userMessage").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "Please inspect this"))))
            .put(JSONObject().put("id", "reason-1").put("type", "reasoning").put("summary", JSONArray()
                .put("Checking files").put("Comparing output")))
        val response = JSONObject().put("data", JSONArray().put(JSONObject()
            .put("id", "turn-1").put("status", "completed").put("items", items)))
        calls.single().done(response, null)

        assertEquals(listOf("Please inspect this", "Checking files Comparing output"),
            controller.uiState.value.history.entries.map { it.summary })
    }

    @Test fun errorsRemainRetryable() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/goal/get"))
        controller.refreshGoal()
        calls.removeAt(0).done(null, "temporary")
        assertEquals("temporary", controller.uiState.value.goal.error)
        controller.retry()
        assertEquals("thread/goal/get", calls.single().method)
    }

    private fun goal(objective: String) = JSONObject()
        .put("objective", objective).put("status", "active").put("tokenBudget", 1000)
        .put("tokensUsed", 900).put("timeUsedSeconds", 12).put("createdAt", 1).put("updatedAt", 2)
}
