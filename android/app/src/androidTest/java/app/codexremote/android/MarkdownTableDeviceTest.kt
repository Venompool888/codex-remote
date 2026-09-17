@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.conversation.ComposeMarkdown
class MarkdownTableDeviceTest : InstrumentationTestCase() {
    fun testColumnsRemainReadableAndCopyRetainsMarkdown() {
        val source = "| 文件 | 类型 | 大小 |\n| :--- | :--- | ---: |\n| **笔记.md** | Markdown | 12 KB |\n| 报告.pdf | PDF | 480 KB |"
        var copied = ""
        val activity = instrumentation.composeFixture { ComposeMarkdown(source, onCopyCode = { copied = it }, modifier = Modifier.width(240.dp)) }
        try { instrumentation.awaitUiText("笔记.md"); instrumentation.clickUi("Copy"); assertEquals(source, copied)
            val root = instrumentation.awaitUiText("报告.pdf")
            val scroll = root.uiDescendants().firstOrNull { it.actionList.any { action -> action.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            assertNotNull("Narrow table exposes horizontal scrolling: " + root.uiDescendants().joinToString { "${it.text}:${it.actionList.map { a -> a.id }}" }, scroll)
            assertTrue(scroll!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
