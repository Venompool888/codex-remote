@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Rect
import android.test.InstrumentationTestCase
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.conversation.UserMessageBubble

class AttachmentImageFlowDeviceTest : InstrumentationTestCase() {
    fun testNarrowRtlAttachmentReflow() {
        val item = TimelineItem("user", "You", "Three files", TimelineItem.Kind.USER, attachments = listOf(
            MessageAttachment("1", "first-report.txt", "file"), MessageAttachment("2", "second-report.txt", "file"), MessageAttachment("3", "third-report.txt", "file")))
        val activity = instrumentation.composeFixture { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            UserMessageBubble(item, false, {}, Modifier.width(220.dp))
        } }
        try {
            val root = instrumentation.awaitUiText("third-report.txt")
            val boxes = listOf("first-report.txt", "second-report.txt", "third-report.txt").map { name ->
                Rect().also(root.findUiText(name).first()::getBoundsInScreen)
            }
            boxes.forEach { assertTrue("Visible file label", it.width() > 0 && it.height() > 0) }
            for (i in boxes.indices) for (j in 0 until i) assertFalse("Files cannot overlap", Rect.intersects(boxes[i], boxes[j]))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
