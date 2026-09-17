@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Rect
import android.test.InstrumentationTestCase
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.conversation.UserMessageBubble
import androidx.compose.runtime.CompositionLocalProvider
import app.codexremote.android.ui.LocalRemoteAttachmentOpener
class SentFileCardDeviceTest : InstrumentationTestCase() {
    fun testSentFileCardInvokesOriginalAttachmentCallback() {
        val attachment = MessageAttachment("12345678-1234-1234-1234-123456789abc", "historical-notes.txt", "file")
        var opened: MessageAttachment? = null
        val activity = instrumentation.composeFixture {
            CompositionLocalProvider(LocalRemoteAttachmentOpener provides { opened = it }) {
                UserMessageBubble(TimelineItem("history", "You", "", TimelineItem.Kind.USER,
                    attachments = listOf(attachment)), false, {}, Modifier.width(240.dp))
            }
        }
        try {
            instrumentation.awaitUiText("historical-notes.txt")
            instrumentation.clickUi("historical-notes.txt")
            assertEquals(attachment, opened)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testFileOnlyAndMixedTextAtNarrowWidth() {
        val names=listOf("reference.pdf","project-review-with-a-long-document-name.pdf","notes.txt")
        val activity=instrumentation.composeFixture {Column(Modifier.width(280.dp)){names.forEachIndexed {i,name->UserMessageBubble(TimelineItem("$i","You",if(i==2)"Review these notes." else "",TimelineItem.Kind.USER,attachments=listOf(MessageAttachment("$i",name,"file"))),false,{})}}}
        try {val root=instrumentation.awaitUiText("Review these notes.");names.forEach {name->val bounds=Rect().also(root.findUiText(name).first()::getBoundsInScreen);assertTrue(bounds.width()>0)}}
        finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
