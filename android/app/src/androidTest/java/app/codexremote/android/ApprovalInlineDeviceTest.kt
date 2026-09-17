@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import app.codexremote.android.presentation.composer.ComposerController
import app.codexremote.android.ui.composer.ComposerSection
import app.codexremote.android.ui.interactions.ApprovalDialog
import app.codexremote.android.ui.interactions.ApprovalUiChoice
import app.codexremote.android.ui.interactions.ApprovalUiState

/** Disposable emulator only: checks the real panel, input, and decision accessibility. */
class ApprovalInlineDeviceTest : InstrumentationTestCase() {
    fun testShortApprovalLeavesConversationAndComposerReachable() {
        val state = mutableStateOf<ApprovalUiState?>(ApprovalUiState("first", "Allow command?", "hostname -I",
            listOf(ApprovalUiChoice("allow", "Allow"), ApprovalUiChoice("deny", "Deny"))))
        val composer = ComposerController()
        val replies = mutableListOf<String>()
        val activity = instrumentation.composeFixture {
            Column(Modifier.fillMaxSize()) {
                Text("Conversation remains visible")
                Spacer(Modifier.weight(1f))
                ApprovalDialog(state.value, onChoice = { replies += it })
                ComposerSection(composer)
            }
        }
        try {
            instrumentation.awaitUiText("Allow command?")
            val root = instrumentation.awaitUiText("Conversation remains visible")
            assertTrue("Composer must be in the same accessible window", root.uiDescendants().any { it.isEditable })
            val title = Rect().also(root.findUiText("Allow command?").first()::getBoundsInScreen)
            val editor = Rect().also(root.uiDescendants().first { it.isEditable }::getBoundsInScreen)
            assertTrue("Approval sits above input", title.top < editor.top)
            assertTrue("Short approval leaves at least a third of the screen for conversation", title.top > activity.resources.displayMetrics.heightPixels / 3)
            assertTrue("Rendering never sends an approval", replies.isEmpty())
            var allowButton = instrumentation.uiAutomation.rootInActiveWindow.uiDescendants().first { it.text?.toString() == "Allow" }
            while (!allowButton.isClickable) allowButton = allowButton.parent ?: error("Allow has no click action")
            assertTrue(allowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(listOf("allow"), replies)
            instrumentation.runOnMainSync { state.value = state.value!!.copy(isBusy = true) }
            instrumentation.awaitUi("Busy decisions disabled") { tree ->
                tree.uiDescendants().filter { it.text?.toString() == "Allow" }.any { label ->
                    generateSequence(label) { it.parent }.any { !it.isEnabled }
                }
            }
            instrumentation.runOnMainSync { state.value = state.value!!.copy(isBusy = false, error = "Host rejected this reply") }
            instrumentation.awaitUiText("Host rejected this reply")
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                instrumentation.targetContext.openFileOutput("qa-inline-approval.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { state.value = null }
            instrumentation.awaitUi("Expired approval removed") { it.findUiText("Allow command?").isEmpty() }
            assertEquals("Expiry never accepts or denies automatically", 1, replies.size)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
