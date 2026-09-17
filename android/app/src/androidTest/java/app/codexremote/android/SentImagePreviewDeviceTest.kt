@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Bitmap
import android.graphics.Rect
import android.test.InstrumentationTestCase
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.LocalRemoteImageRepository
import app.codexremote.android.ui.conversation.UserMessageBubble
class SentImagePreviewDeviceTest : InstrumentationTestCase() {
    fun testPortraitAndLandscapeContentRemainVisible() {
        for((w,h) in listOf(400 to 1000,1000 to 300,32 to 32)) {
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            val repository=imageFixtureRepository(instrumentation.targetContext.cacheDir,bitmap)
            val item=TimelineItem("user","You","",TimelineItem.Kind.USER,attachments=listOf(MessageAttachment("12345678-1234-1234-1234-123456789abc","preview.png","image")))
            val activity=instrumentation.composeFixture {CompositionLocalProvider(LocalRemoteImageRepository provides repository){UserMessageBubble(item,false,{},Modifier.width(260.dp))}}
            try {val root=instrumentation.awaitUi("sent image preview") {it.uiDescendants().any {n->n.contentDescription=="preview.png"}}
                val bounds=Rect().also(root.uiDescendants().first {it.contentDescription=="preview.png"}::getBoundsInScreen)
                assertTrue(bounds.width()>0&&bounds.height()>0);assertTrue(bounds.height()<=320*activity.resources.displayMetrics.density+1)
            } finally {instrumentation.runOnMainSync {activity.finish()};bitmap.recycle()}
        }
    }
}
