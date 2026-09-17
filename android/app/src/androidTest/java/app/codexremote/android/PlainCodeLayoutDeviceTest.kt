@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.conversation.ComposeMarkdown
class PlainCodeLayoutDeviceTest : InstrumentationTestCase() {
    fun testPlainTextWrapsButProgrammingCodeScrollsWithoutChangingSource() {
        val source="遇到现有实现已经满足要求的部分，验证后复用；遇到协议或平台限制，先检查官方文档和实际主机能力，采用范围内可行方案。不要在仍有安全、明确且已授权的工作可做时停下来只报告计划。"
        for(language in listOf("","text","plaintext","kotlin")) {
            var copied=""
            val activity=instrumentation.composeFixture { ComposeMarkdown("```$language\n$source\n```",onCopyCode={copied=it},modifier=Modifier.width(240.dp)) }
            try {
                val root=instrumentation.awaitUiText(source)
                assertEquals("Only programming fences scroll horizontally: $language",language=="kotlin",root.uiDescendants().any { it.isScrollable })
                instrumentation.clickUi("Copy"); assertEquals(source,copied)
            } finally { instrumentation.runOnMainSync {activity.finish()} }
        }
    }
    fun testNestedFenceExampleDisplaysAndCopiesAsOneBlock() {
        val source="Example:\n```kotlin\nval answer = 42\n```\nKeep the inner fences."; var copied=""
        val activity=instrumentation.composeFixture { ComposeMarkdown("````text\n$source\n````",onCopyCode={copied=it}) }
        try { val root=instrumentation.awaitUiText(source);assertEquals(1,root.findUiText(source).size);instrumentation.clickUi("Copy");assertEquals(source,copied) }
        finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
