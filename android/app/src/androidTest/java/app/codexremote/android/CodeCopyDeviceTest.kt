@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.ui.conversation.MarkdownCodeBlockComposable
class CodeCopyDeviceTest : InstrumentationTestCase() {
    fun testCopyPreservesExactSourceAndFeedbackResets() {
        val source = "value = '\$literal'\nprint(value)\n"; var copied = ""
        val activity = instrumentation.composeFixture { MarkdownCodeBlockComposable(source, "python", { copied = source }) }
        try { instrumentation.clickUi("Copy"); instrumentation.awaitUiText("Copied"); assertEquals(source, copied)
            instrumentation.awaitUi("Copy feedback resets", 4000) { it.findUiText("Copied").isEmpty() && it.findUiText("Copy").isNotEmpty() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
