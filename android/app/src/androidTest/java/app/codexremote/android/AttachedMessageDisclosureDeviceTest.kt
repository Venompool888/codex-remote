@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import app.codexremote.android.ui.conversation.UserMessageBubble
class AttachedMessageDisclosureDeviceTest : InstrumentationTestCase() {
    fun testAttachmentDoesNotPreventLongTextDisclosure() {
        val text=(1..30).joinToString("\n"){"QA message line $it"};val expanded=mutableStateOf(false)
        val item=TimelineItem("qa","You",text,TimelineItem.Kind.USER,attachments=listOf(MessageAttachment("file","reference.pdf","file")))
        val activity=instrumentation.composeFixture {Column(Modifier.verticalScroll(rememberScrollState())) {UserMessageBubble(item,expanded.value,{expanded.value=!expanded.value})}}
        try {instrumentation.awaitUiText("reference.pdf");instrumentation.clickUi("Show more");instrumentation.awaitUiText("QA message line 30");assertTrue(expanded.value)}
        finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
