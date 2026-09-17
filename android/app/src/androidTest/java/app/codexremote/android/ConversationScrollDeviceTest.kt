@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.ui.conversation.ConversationScreen

class ConversationScrollDeviceTest : InstrumentationTestCase() {
    fun testStreamingPreservesHistoryAndJumpReturnsToLatest() {
        val controller = ConversationController()
        val items = (1..25).map { TimelineItem("m$it", "", "MESSAGE_$it\n" + "History content.\n".repeat(4), TimelineItem.Kind.ASSISTANT) }
        instrumentation.runOnMainSync { controller.setTimelineItems(items, true) }
        val activity = instrumentation.composeFixture { ConversationScreen(controller, {}) }
        try {
            instrumentation.awaitUiText("MESSAGE_25")
            repeat(3) {
                val scroll = instrumentation.uiAutomation.rootInActiveWindow.uiDescendants().first { node ->
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }
                }
                assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                instrumentation.uiAutomation.waitForIdle(400, 5000)
                android.os.SystemClock.sleep(600)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val before = instrumentation.uiAutomation.rootInActiveWindow.uiDescendants().mapNotNull { it.text?.toString() }.filter { it.startsWith("MESSAGE_") }
            assertTrue(before.isNotEmpty())
            assertFalse(before.any { it.startsWith("MESSAGE_25\n") })
            instrumentation.runOnMainSync { controller.setTimelineItems(items.dropLast(1) + items.last().copy(text = items.last().text + "Streaming more.\n".repeat(20)), true) }
            android.os.SystemClock.sleep(500)
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val after = instrumentation.uiAutomation.rootInActiveWindow.uiDescendants().mapNotNull { it.text?.toString() }.filter { it.startsWith("MESSAGE_") }
            assertEquals("New output must not interrupt history reading", before, after)
            val jumpRoot = instrumentation.awaitUi("jump to latest") { it.uiDescendants().any { n -> n.contentDescription == "Scroll to latest message" } }
            var jump = jumpRoot.uiDescendants().first { it.contentDescription == "Scroll to latest message" }
            while (!jump.isClickable) jump = jump.parent ?: error("Missing jump action")
            assertTrue(jump.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.awaitUiText("Streaming more.")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
