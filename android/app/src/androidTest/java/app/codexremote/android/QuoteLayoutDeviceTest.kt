@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.conversation.ComposeMarkdown
class QuoteLayoutDeviceTest : InstrumentationTestCase() {
    fun testQuoteKeepsCompleteText() {
        val activity=instrumentation.composeFixture { ComposeMarkdown("> 保持专注，从整理桌面开始。\n> 每天留出两分钟归还物品。",modifier=Modifier.width(240.dp)) }
        try { val root=instrumentation.awaitUiText("每天留出两分钟归还物品。");assertTrue(root.findUiText("保持专注，从整理桌面开始。").isNotEmpty()) }
        finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
