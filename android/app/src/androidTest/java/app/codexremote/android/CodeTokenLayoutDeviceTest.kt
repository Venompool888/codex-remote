@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.ui.conversation.ComposeMarkdown
class CodeTokenLayoutDeviceTest : InstrumentationTestCase() {
    fun testCodeKeepsSourceAndDistinctSyntaxKinds() {
        val source="# Desk reminder\ndef remind(count=3):\n    message = \"Keep your desk clear\"\n    for index in range(count):\n        print(message, index + 1)"
        var copied=""
        val activity=instrumentation.composeFixture {ComposeMarkdown("```python\n$source\n```",onCopyCode={copied=it})}
        try { instrumentation.awaitUiText("Keep your desk clear");instrumentation.clickUi("Copy");assertEquals(source,copied)
            assertTrue(CodeHighlight.tokens(source,"python").map {it.kind}.toSet().size>=5)
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
