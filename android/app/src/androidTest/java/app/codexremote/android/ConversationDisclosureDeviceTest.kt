@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.ui.conversation.ConversationScreen
import app.codexremote.android.ui.conversation.ToolActivityCard

class ConversationDisclosureDeviceTest : InstrumentationTestCase() {
    fun testCompletionCollapsesAutomaticDetailsButKeepsUserChoice() {
        val item = mutableStateOf(TimelineItem("proof", "Working", "", TimelineItem.Kind.ACTIVITY_GROUP,
            children = listOf(TimelineItem("child", "Codex", "Read-only test", TimelineItem.Kind.COMMENTARY)), active = true))
        val selected = mutableStateOf(false)
        val activity = instrumentation.composeFixture {
            ToolActivityCard(item.value, selected.value, { selected.value = !selected.value }, { _, _ -> })
        }
        try {
            instrumentation.awaitUiText("Read-only test")
            instrumentation.runOnMainSync { item.value = item.value.copy(label = "Completed", active = false) }
            instrumentation.awaitUi("auto details collapsed") { it.findUiText("Read-only test").isEmpty() }
            instrumentation.clickUi("Completed")
            instrumentation.awaitUiText("Read-only test")
            instrumentation.runOnMainSync { item.value = item.value.copy(durationMs = 1000) }
            instrumentation.awaitUiText("Read-only test")
            assertTrue(selected.value)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testWorkingProseAndToolDisclosureThenFinalAnswer() {
        val controller = ConversationController()
        val user = TimelineItem("user", "You", "Check the message flow", TimelineItem.Kind.USER)
        val tool = TimelineItem("search", "Search source", "SEARCH_OUTPUT_SECRET", TimelineItem.Kind.COMMAND,
            rawCommand = "rg message source", toolStyle = TimelineItem.ToolStyle.SEARCH, active = true)
        val group = TimelineItem("work", "Working", "TICKER_ONLY", TimelineItem.Kind.ACTIVITY_GROUP,
            active = true, children = listOf(
                TimelineItem("progress", "Codex", "I am checking the message flow.", TimelineItem.Kind.COMMENTARY),
                tool,
                TimelineItem("read", "ConversationScreen.kt", "FILE_CONTENT_PRIVATE", TimelineItem.Kind.COMMAND, toolStyle = TimelineItem.ToolStyle.READ),
                TimelineItem("wait", "wait", "WAIT_OUTPUT_PRIVATE", TimelineItem.Kind.TOOL),
                TimelineItem("finding", "Codex", "The progress text should remain readable.", TimelineItem.Kind.COMMENTARY)
            ))
        instrumentation.runOnMainSync {
            controller.updateThread("flow-qa", "Message flow", "/tmp", "QA", "local", true)
            controller.setTimelineItems(listOf(user, group), true)
        }
        val activity = instrumentation.composeFixture { ConversationScreen(controller, {}) }
        try {
            val workingRoot = instrumentation.awaitUiText("The progress text should remain readable.")
            assertTrue(workingRoot.findUiText("I am checking the message flow.").isNotEmpty())
            assertTrue("Tool output is hidden by default", workingRoot.findUiText("SEARCH_OUTPUT_SECRET").isEmpty())
            assertTrue("Raw command is hidden by default", workingRoot.findUiText("rg message source").isEmpty())
            assertTrue("No Working group header while running", workingRoot.findUiText("Working").isEmpty())
            assertTrue("No per-message Codex heading", workingRoot.findUiText("Codex").isEmpty())
            capture("qa-message-stream-working.png")
            instrumentation.clickUi("Search source")
            instrumentation.awaitUiText("SEARCH_OUTPUT_SECRET")
            instrumentation.awaitUiText("rg message source")
            val updated = group.copy(children = group.children.map {
                if (it.id == tool.id) it.copy(text = "SEARCH_OUTPUT_UPDATED") else it
            })
            instrumentation.runOnMainSync { controller.setTimelineItems(listOf(user, updated), true) }
            instrumentation.awaitUiText("SEARCH_OUTPUT_UPDATED")
            instrumentation.clickUi("Search source")
            instrumentation.awaitUi("tool can close during execution") { it.findUiText("SEARCH_OUTPUT_UPDATED").isEmpty() }

            val finished = updated.copy(label = "Completed", phase = "completed", active = false, durationMs = 65_000,
                children = updated.children.map { it.copy(active = false) })
            val final = TimelineItem("final", "Codex", "The message flow is fixed.", TimelineItem.Kind.ASSISTANT)
            // A final answer can arrive before the turn-completed event.
            instrumentation.runOnMainSync { controller.setTimelineItems(listOf(user, finished, final), true) }
            val finalRoot = instrumentation.awaitUi("final is visible and process is collapsed") {
                it.findUiText("The message flow is fixed.").isNotEmpty() &&
                    it.findUiText("I am checking the message flow.").isEmpty()
            }
            assertTrue(finalRoot.findUiText("I am checking the message flow.").isEmpty())
            assertTrue(finalRoot.findUiText("Search source").isEmpty())
            assertTrue("Final answer must not restart Thinking", finalRoot.findUiText("Thinking").isEmpty())
            instrumentation.awaitUiText("Worked for 1m 5s")
            capture("qa-message-stream-complete.png")
            instrumentation.runOnMainSync { controller.setTimelineItems(listOf(user, finished, final), false) }
            instrumentation.clickUi("Worked for 1m 5s")
            val reopened = instrumentation.awaitUiText("The progress text should remain readable.")
            assertTrue(reopened.findUiText("SEARCH_OUTPUT_UPDATED").isEmpty())
            instrumentation.clickUi("Search source")
            instrumentation.awaitUiText("SEARCH_OUTPUT_UPDATED")
            instrumentation.awaitUiText("The message flow is fixed.")
            capture("qa-message-stream-expanded.png")
            instrumentation.clickUi("Search source")
            instrumentation.awaitUiText("I am checking the message flow.")
            instrumentation.clickUi("Worked for 1m 5s")
            instrumentation.awaitUi("whole process closes") { it.findUiText("I am checking the message flow.").isEmpty() }
            instrumentation.awaitUiText("The message flow is fixed.")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testStandaloneActiveToolDoesNotForceDetailsOpen() {
        val controller = ConversationController()
        val tool = TimelineItem("standalone", "Run checks", "PRIVATE_CHECK_OUTPUT", TimelineItem.Kind.COMMAND,
            rawCommand = "check --verbose", active = true)
        instrumentation.runOnMainSync { controller.setTimelineItems(listOf(tool), true) }
        val activity = instrumentation.composeFixture { ConversationScreen(controller, {}) }
        try {
            val root = instrumentation.awaitUiText("Run checks")
            assertTrue(root.findUiText("PRIVATE_CHECK_OUTPUT").isEmpty())
            instrumentation.clickUi("Run checks")
            instrumentation.awaitUiText("PRIVATE_CHECK_OUTPUT")
            instrumentation.clickUi("Run checks")
            instrumentation.awaitUi("standalone details close") { it.findUiText("PRIVATE_CHECK_OUTPUT").isEmpty() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testToolChoicesDoNotCrossThreadsWithMatchingItemIds() {
        val controller = ConversationController()
        val tool = TimelineItem("same-tool-id", "Inspect file", "TASK_SPECIFIC_OUTPUT", TimelineItem.Kind.COMMAND)
        val group = TimelineItem("same-group-id", "Working", "", TimelineItem.Kind.ACTIVITY_GROUP,
            active = true, children = listOf(tool))
        instrumentation.runOnMainSync {
            controller.updateThread("thread-a", "First task", "/tmp", "QA", "local", true)
            controller.setTimelineItems(listOf(group), true)
        }
        val activity = instrumentation.composeFixture { ConversationScreen(controller, {}) }
        try {
            instrumentation.clickUi("Inspect file")
            instrumentation.awaitUiText("TASK_SPECIFIC_OUTPUT")
            instrumentation.runOnMainSync {
                controller.updateThread("thread-b", "Second task", "/tmp", "QA", "local", true)
            }
            instrumentation.awaitUiText("Second task")
            instrumentation.awaitUi("new task starts with tool closed") { it.findUiText("TASK_SPECIFIC_OUTPUT").isEmpty() }
            instrumentation.clickUi("Inspect file")
            instrumentation.awaitUiText("TASK_SPECIFIC_OUTPUT")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testFailedAndCancelledGroupsKeepDetailsAvailable() {
        val selected = mutableStateOf(false)
        val item = mutableStateOf(TimelineItem("failed", "Failed", "", TimelineItem.Kind.ACTIVITY_GROUP,
            phase = "failed", durationMs = 2000, children = listOf(
                TimelineItem("error", "Check failed", "FAILURE_DETAIL", TimelineItem.Kind.COMMAND, phase = "failed")
            )))
        val activity = instrumentation.composeFixture {
            ToolActivityCard(item.value, selected.value, { selected.value = !selected.value }, { _, _ -> })
        }
        try {
            val failed = instrumentation.awaitUiText("Failed in 2s")
            assertTrue(failed.findUiText("FAILURE_DETAIL").isEmpty())
            instrumentation.clickUi("Failed in 2s")
            instrumentation.clickUi("Check failed")
            instrumentation.awaitUiText("FAILURE_DETAIL")
            instrumentation.clickUi("Failed in 2s")
            instrumentation.runOnMainSync { item.value = item.value.copy(phase = "cancelled", label = "Cancelled") }
            instrumentation.awaitUiText("Cancelled in 2s")
            instrumentation.clickUi("Cancelled in 2s")
            instrumentation.awaitUiText("Check failed")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Missing screenshot")
        instrumentation.targetContext.openFileOutput(name, 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
