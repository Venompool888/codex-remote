@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Rect
import android.test.InstrumentationTestCase
import app.codexremote.android.ui.conversation.ComposeMarkdown
class NumberedListDeviceTest : InstrumentationTestCase() {
    fun testStartNumberAndLongMarkersStayOnOneLine() {
        for(start in listOf(9,999999999)) {
            val activity=instrumentation.composeFixture {ComposeMarkdown("$start. 整理文件\n1. 核对报告")}
            try {
                val root=instrumentation.awaitUiText("核对报告")
                val first=root.findUiText("$start.").first();val second=root.findUiText("${start.toLong()+1}.").first()
                val bounds=Rect().also(first::getBoundsInScreen);val next=Rect().also(second::getBoundsInScreen)
                assertTrue(bounds.width()>0);assertTrue(next.top>=bounds.bottom)
            } finally {instrumentation.runOnMainSync {activity.finish()}}
        }
    }
    fun testBulletBodyAndWrappedLinesRemainComplete() {
        val activity=instrumentation.composeFixture {ComposeMarkdown("- 草稿持久化\n- 离线待发送箱，但恢复后发送前再次确认\n- Markdown、代码块、表格和工具结果的完整渲染")}
        try {val root=instrumentation.awaitUiText("Markdown、代码块、表格和工具结果的完整渲染");assertTrue(root.findUiText("草稿持久化").isNotEmpty());assertTrue(root.findUiText("离线待发送箱，但恢复后发送前再次确认").isNotEmpty())}
        finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
