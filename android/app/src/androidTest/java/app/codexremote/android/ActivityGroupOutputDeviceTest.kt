@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.ui.conversation.ToolActivityCard

class ActivityGroupOutputDeviceTest : InstrumentationTestCase() {
    fun testGroupTickerIsNotRenderedAsAnotherCommandOutput() {
        val child = TimelineItem("command", "Running hostname", "COMMAND_RESULT", TimelineItem.Kind.COMMAND, rawCommand = "hostname -I")
        val item = mutableStateOf(TimelineItem("group", "Working", "GROUP_TICKER_ONLY", TimelineItem.Kind.ACTIVITY_GROUP,
            children = listOf(child), active = true))
        val activity = instrumentation.composeFixture { ToolActivityCard(item.value, true, {}, { _, _ -> }) }
        try {
            val collapsed = instrumentation.awaitUiText("Running hostname")
            assertTrue(collapsed.findUiText("COMMAND_RESULT").isEmpty())
            instrumentation.clickUi("Running hostname")
            val root = instrumentation.awaitUiText("COMMAND_RESULT")
            assertTrue("A group's ticker is a summary, not a second output", root.findUiText("GROUP_TICKER_ONLY").isEmpty())
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                instrumentation.targetContext.openFileOutput("qa-activity-output.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { item.value = child.copy(text = "STANDALONE_COMMAND_RESULT") }
            instrumentation.awaitUiText("STANDALONE_COMMAND_RESULT")
            instrumentation.awaitUiText("hostname -I")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
