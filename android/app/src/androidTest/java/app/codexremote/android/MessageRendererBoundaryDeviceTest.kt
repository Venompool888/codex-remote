@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.ui.conversation.ComposeMarkdown
import app.codexremote.android.ui.conversation.FileDiffDialog

class MessageRendererBoundaryDeviceTest : InstrumentationTestCase() {
    fun testMarkdownUsesExplicitArtifactCallbackWithoutActivityRenderer() {
        var clicked = 0
        val activity = instrumentation.composeFixture { ComposeMarkdown("[Report](remote-artifact://report)", onOpenArtifacts = { clicked++ }) }
        try { instrumentation.clickUi("Report"); instrumentation.awaitUi("artifact callback") { clicked == 1 } }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testToolDisclosureIsLocalToControllerAndResettable() {
        val first = ConversationController(); val second = ConversationController()
        instrumentation.runOnMainSync {
            first.toggleGroupExpansion("tool")
            assertTrue("tool" in first.uiState.value.expandedGroups); assertFalse("tool" in second.uiState.value.expandedGroups)
            first.resetDisclosures(); assertFalse("tool" in first.uiState.value.expandedGroups)
        }
    }
    fun testDiffSelectionCopiesExactSelectedPatch() {
        val first = FileDiffDetail(path = "first.kt", kind = "update", patch = "-old\n+first\n")
        val second = FileDiffDetail(path = "second.kt", kind = "update", patch = "-old\n+second\n")
        val selected = mutableStateOf(first.path); var copied = ""
        val activity = instrumentation.composeFixture { FileDiffDialog(listOf(first, second), selected.value, { selected.value = it }, { copied = it }, {}) }
        try {
            instrumentation.clickUi("second.kt"); instrumentation.clickUi("Copy patch")
            instrumentation.awaitUi("selected exact patch copied") { copied == second.patch }
            assertEquals(second.patch, copied)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
