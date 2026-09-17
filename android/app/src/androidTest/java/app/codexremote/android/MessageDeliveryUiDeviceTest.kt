@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.presentation.conversation.MessageDeliveryStatus
import app.codexremote.android.ui.conversation.UserMessageBubble

class MessageDeliveryUiDeviceTest : InstrumentationTestCase() {
    fun testSendingDotsCycleThenSentThenReceiptDisappears() {
        val status = mutableStateOf<MessageDeliveryStatus?>(MessageDeliveryStatus.SENDING)
        val activity = instrumentation.composeFixture {
            UserMessageBubble(TimelineItem("delivery", "You", "Message delivery preview", TimelineItem.Kind.USER), false, {},
                modifier = Modifier.statusBarsPadding().padding(16.dp), deliveryStatus = status.value)
        }
        try {
            instrumentation.awaitUiText("Message delivery preview")
            val seen = mutableSetOf<String>()
            val deadline = SystemClock.uptimeMillis() + 2500L
            while (SystemClock.uptimeMillis() < deadline && seen.size < 3) {
                if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                instrumentation.uiAutomation.rootInActiveWindow?.uiDescendants()?.mapNotNull { it.text?.toString() }
                    ?.filter { it.matches(Regex("sending\\.{1,3}")) }?.let(seen::addAll)
                SystemClock.sleep(60)
            }
            assertEquals(setOf("sending.", "sending..", "sending..."), seen)
            instrumentation.runOnMainSync { status.value = MessageDeliveryStatus.SENT }
            instrumentation.awaitUiText("sent")
            SystemClock.sleep(600)
            val sent = instrumentation.awaitUiText("sent")
            assertTrue(sent.findUiText("sending").isEmpty())
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                instrumentation.targetContext.openFileOutput("qa-message-sent.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { status.value = null }
            instrumentation.awaitUi("receipt removed after turn completion") { root -> root.findUiText("sent").isEmpty() && root.findUiText("sending").isEmpty() }
            instrumentation.awaitUiText("Message delivery preview")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
