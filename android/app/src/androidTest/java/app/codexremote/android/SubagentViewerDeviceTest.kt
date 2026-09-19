@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import android.view.KeyEvent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.presentation.conversation.SubagentViewerController
import app.codexremote.android.ui.conversation.ConversationScreen
import app.codexremote.android.ui.conversation.SubagentDirectory
import app.codexremote.android.ui.conversation.SubagentStrip
import app.codexremote.android.ui.conversation.SubagentViewer
import org.json.JSONObject

/** Synthetic UI fixture; execute only on a disposable emulator. */
class SubagentViewerDeviceTest : InstrumentationTestCase() {
    fun testFiltersKeepSelectionAcrossUpdatesAndAllowPagination() {
        val entries = androidx.compose.runtime.mutableStateOf(listOf(
            SubagentDirectoryEntry("layout", "Layout review", "completed"),
            SubagentDirectoryEntry("copy", "Copy review", "running", parentId = "layout"),
            SubagentDirectoryEntry("touch", "Touch review", "errored"),
            SubagentDirectoryEntry("idle", "Idle reviewer", "idle"),
        ))
        var loads = 0
        var inspected: String? = null
        val activity = instrumentation.composeFixture {
            SubagentDirectory(entries.value, false, null, true, {}, { loads++ }, { inspected = it },
                modifier = Modifier.safeDrawingPadding())
        }
        try {
            instrumentation.awaitUiText("Layout review")
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                instrumentation.targetContext.openFileOutput("qa-subagent-directory-a.png", android.content.Context.MODE_PRIVATE)
                    .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            instrumentation.clickUi("Running (1)")
            instrumentation.awaitUi("Only running task") { root ->
                root.findUiText("Copy review").isNotEmpty() && root.findUiText("Idle reviewer").isEmpty()
            }
            instrumentation.clickUi("Copy review")
            instrumentation.runOnMainSync { assertEquals("copy", inspected) }
            instrumentation.runOnMainSync {
                entries.value = entries.value.map { if (it.id == "copy") it.copy(status = "completed") else it }
            }
            instrumentation.awaitUi("Running filter remains selected after completion") { root ->
                root.findUiText("Running (0)").isNotEmpty() && root.findUiText("Copy review").isEmpty()
            }
            instrumentation.clickUi("Load more")
            instrumentation.runOnMainSync { assertEquals(1, loads) }
            instrumentation.clickUi("Completed (2)")
            instrumentation.awaitUi("Completed excludes idle") { root ->
                root.findUiText("Layout review").isNotEmpty() && root.findUiText("Copy review").isNotEmpty() &&
                    root.findUiText("Idle reviewer").isEmpty()
            }
            instrumentation.clickUi("Needs attention (1)")
            instrumentation.awaitUiText("Touch review")
            instrumentation.clickUi("All (4)")
            instrumentation.awaitUiText("Idle reviewer")
            instrumentation.runOnMainSync { entries.value = emptyList() }
            instrumentation.awaitUiText("Load more")
            instrumentation.clickUi("Load more")
            instrumentation.runOnMainSync { assertEquals(2, loads) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testDirectoryInspectionAndExplicitDirectInput() {
        var inspected: String? = null
        val directoryActivity = instrumentation.composeFixture {
            SubagentDirectory(
                entries = listOf(SubagentDirectoryEntry("a", "Worker Alpha", "active", role = "Reviewer"),
                    SubagentDirectoryEntry("b", "Worker Beta", "idle", parentId = "a", canAcceptDirectInput = true)),
                loading = false, error = null, canLoadMore = false,
                onRefresh = {}, onLoadMore = {}, onInspect = { inspected = it },
            )
        }
        try {
            instrumentation.awaitUiText("Worker Alpha")
            instrumentation.clickUi("Worker Beta")
            instrumentation.runOnMainSync { assertEquals("b", inspected) }
        } finally { instrumentation.runOnMainSync { directoryActivity.finish() } }

        var opened: String? = null
        val viewer = SubagentViewerController(onOpenAsTask = { opened = it }) { id, done ->
            done(JSONObject().put("id", id).put("canAcceptDirectInput", true)
                .put("status", JSONObject().put("type", "idle")).put("turns", org.json.JSONArray()), null)
        }
        instrumentation.runOnMainSync { viewer.open(SubagentReference("controllable", "Worker")) }
        val activity = instrumentation.composeFixture { SubagentViewer(viewer) }
        try {
            instrumentation.awaitUiText("This subagent accepts direct input")
            instrumentation.clickUi("Open task")
            instrumentation.runOnMainSync { assertEquals("controllable", opened) }
        } finally { instrumentation.runOnMainSync { viewer.close(); activity.finish() } }
    }

    fun testOpenRetryReadHistoryAndReturnToParent() {
        var reads = 0
        val viewer = SubagentViewerController { id, done ->
            reads++
            assertEquals("fixture-child", id)
            if (reads == 1) done(null, "Unavailable")
            else done(JSONObject("""{
              "id":"fixture-child","agentNickname":"Reviewer","status":{"type":"active"},
              "turns":[{"id":"turn","status":"inProgress","items":[
                {"id":"message","type":"agentMessage","text":"CHILD_PROGRESS_VISIBLE"},
                {"id":"tool","type":"commandExecution","command":"fixture-check",
                 "status":"completed","aggregatedOutput":"CHILD_TOOL_RESULT"}
              ]}]
            }"""), null)
        }
        val controller = ConversationController(subagents = viewer)
        instrumentation.runOnMainSync {
            controller.updateThread("fixture-parent", "Parent task", "", "", "", true)
            controller.setTimelineItems(listOf(
                TimelineItem("parent-message", "", "PARENT_STAYS_VISIBLE", TimelineItem.Kind.ASSISTANT),
                TimelineItem("spawn", "Subagents", "", TimelineItem.Kind.TOOL,
                    subagents = listOf(SubagentReference("fixture-child", "Reviewer", "running"))),
            ), false)
        }
        val activity = instrumentation.composeFixture { ConversationScreen(controller, {}) }
        try {
            instrumentation.clickUi("Reviewer")
            instrumentation.awaitUiText("Could not load this subagent")
            val retryRoot = instrumentation.awaitUi("Retry button") { root ->
                root.uiDescendants().any { it.text?.toString() == "Retry" }
            }
            var retry = retryRoot.uiDescendants().first { it.text?.toString() == "Retry" }
            while (!retry.isClickable) retry = retry.parent ?: error("Retry has no button")
            assertTrue(retry.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.awaitUiText("CHILD_PROGRESS_VISIBLE")
            instrumentation.clickUi("$ fixture-check")
            instrumentation.awaitUiText("CHILD_TOOL_RESULT")
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                instrumentation.targetContext.openFileOutput("qa-subagent-viewer.png", android.content.Context.MODE_PRIVATE)
                    .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.awaitUiText("PARENT_STAYS_VISIBLE")
            instrumentation.runOnMainSync { assertNull(viewer.uiState.value.agent) }
            val stoppedAt = reads
            android.os.SystemClock.sleep(3_300)
            assertEquals(stoppedAt, reads)
        } finally {
            instrumentation.runOnMainSync { viewer.close(); activity.finish() }
        }
    }

    fun testSubagentStripExpansionAndBrowseAll() {
        val child = SubagentReference("fixture-child", "Worker Alpha", "running")
        val items = listOf(
            TimelineItem("spawn", "Subagents", "", TimelineItem.Kind.TOOL,
                subagents = listOf(child))
        )
        val viewer = SubagentViewerController { _, done -> done(null, null) }
        var browseAllCalls = 0
        val activity = instrumentation.composeFixture {
            SubagentStrip(
                items = items,
                viewer = viewer,
                modifier = Modifier.safeDrawingPadding(),
                onBrowseAll = { browseAllCalls++ }
            )
        }
        try {
            instrumentation.awaitUiText("Worker Alpha")
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                instrumentation.targetContext.openFileOutput("qa-subagent-strip-expanded.png", android.content.Context.MODE_PRIVATE)
                    .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            instrumentation.clickUi("All subagents")
            instrumentation.runOnMainSync { assertEquals(1, browseAllCalls) }
            instrumentation.awaitUiText("Worker Alpha")

            instrumentation.clickUi("Subagents (1)")
            instrumentation.awaitUi("Collapse hides child and footer") { root ->
                root.findUiText("Worker Alpha").isEmpty() && root.findUiText("All subagents").isEmpty()
            }
            android.os.SystemClock.sleep(350)
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                instrumentation.targetContext.openFileOutput("qa-subagent-strip-collapsed.png", android.content.Context.MODE_PRIVATE)
                    .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }

            instrumentation.clickUi("Subagents (1)")
            instrumentation.awaitUiText("Worker Alpha")
            instrumentation.runOnMainSync { assertEquals(1, browseAllCalls) }
        } finally {
            instrumentation.runOnMainSync { viewer.close(); activity.finish() }
        }
    }

    fun testSubagentStripEmptyStateWithBrowseAllCallback() {
        val itemsState = androidx.compose.runtime.mutableStateOf(emptyList<TimelineItem>())
        val viewer = SubagentViewerController { _, done -> done(null, null) }
        var browseAllCalls = 0
        val activity = instrumentation.composeFixture {
            SubagentStrip(
                items = itemsState.value,
                viewer = viewer,
                modifier = Modifier.safeDrawingPadding(),
                onBrowseAll = { browseAllCalls++ }
            )
        }
        try {
            instrumentation.awaitUi("Empty items show neither Subagents nor All subagents") { root ->
                root.findUiText("Subagents").isEmpty() && root.findUiText("All subagents").isEmpty()
            }

            val child = SubagentReference("fixture-child-2", "Worker Beta", "running")
            instrumentation.runOnMainSync {
                itemsState.value = listOf(
                    TimelineItem("spawn-2", "Subagents", "", TimelineItem.Kind.TOOL,
                        subagents = listOf(child))
                )
            }
            instrumentation.awaitUiText("Subagents (1)")
            instrumentation.awaitUiText("Worker Beta")
            instrumentation.awaitUiText("All subagents")

            instrumentation.runOnMainSync {
                itemsState.value = emptyList()
            }
            instrumentation.awaitUi("Updating back to empty removes strip") { root ->
                root.findUiText("Subagents").isEmpty() &&
                    root.findUiText("Worker Beta").isEmpty() &&
                    root.findUiText("All subagents").isEmpty()
            }
            instrumentation.runOnMainSync { assertEquals(0, browseAllCalls) }
        } finally {
            instrumentation.runOnMainSync { viewer.close(); activity.finish() }
        }
    }
}
