@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.ui.conversation.ConversationScreen

class ThinkingIndicatorDeviceTest : InstrumentationTestCase() {
    fun testPendingTurnIsVisibleBeforeOutputAndClearsOnCompletionOrDisconnect() {
        val controller = ConversationController()
        val user = TimelineItem("pending-user", "You", "Explain this", TimelineItem.Kind.USER)
        instrumentation.runOnMainSync {
            controller.updateThread("qa-thinking", "Status preview", "/tmp", "QA", "local", true)
            controller.setTimelineItems(listOf(user), true)
        }
        val activity = instrumentation.composeFixture { ConversationScreen(controller, {}) }
        try {
            instrumentation.awaitUiText("Thinking")
            android.os.SystemClock.sleep(450)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "qa-thinking.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { controller.setTimelineItems(listOf(user), false) }
            instrumentation.awaitUi("thinking clears on completion") { it.findUiText("Thinking").isEmpty() }
            instrumentation.runOnMainSync { controller.setTimelineItems(listOf(user), true) }
            instrumentation.awaitUiText("Thinking")
            instrumentation.runOnMainSync {
                controller.updateThread("qa-thinking", "Status preview", "/tmp", "QA", "local", false)
            }
            instrumentation.awaitUi("thinking clears on disconnect") { it.findUiText("Thinking").isEmpty() }
            instrumentation.runOnMainSync {
                controller.updateThread("qa-thinking", "Status preview", "/tmp", "QA", "local", true)
                controller.setTimelineItems(emptyList(), true)
            }
            instrumentation.awaitUiText("Thinking")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
