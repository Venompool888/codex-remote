@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Bitmap
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.codexremote.android.ui.LocalRemoteImageRepository
import app.codexremote.android.ui.LocalRemoteImagePresenter
import app.codexremote.android.ui.conversation.MarkdownImageComposable
import java.util.concurrent.Executor

internal fun imageFixtureRepository(directory: java.io.File, bitmap: Bitmap) = RemoteImageRepository(directory,
    {RemoteImageSource("https://image.invalid","device","task","fixture",true,true){true}},
    {_,_,_,_->error("Cached UI fixture must not use network")},Executor {it.run()},Executor {it.run()}, {bitmap}, {bitmap},{_,_->},{_,_,_->})
class GeneratedImageLayoutDeviceTest : InstrumentationTestCase() {
    fun testImageFitsActualContainerWithoutCaptionAndKeepsAccessibleAction() {
        val bitmap=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888);var opened:Bitmap?=null
        val repository=imageFixtureRepository(instrumentation.targetContext.cacheDir,bitmap)
        val activity=instrumentation.composeFixture {CompositionLocalProvider(LocalRemoteImageRepository provides repository, LocalRemoteImagePresenter provides {content,_->opened=content.bitmap}) {
            MarkdownImageComposable(MarkdownBlock.Image("generated-proof.png","/proof.png"),{_,_->},Modifier.width(240.dp))
        }}
        try {
            val root=instrumentation.awaitUi("generated image") {it.uiDescendants().any {n->n.contentDescription=="generated-proof.png"}}
            val image=root.uiDescendants().first {it.contentDescription=="generated-proof.png"};val bounds=Rect().also(image::getBoundsInScreen)
            assertTrue("Natural image ratio",kotlin.math.abs(bounds.width()-bounds.height()*2)<=2)
            assertTrue("No redundant caption",root.findUiText("generated-proof.png").isEmpty())
            var button=image;repeat(12){if(!button.isClickable)button=button.parent?:button};button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("loaded bitmap opens") {opened===bitmap}
        } finally {instrumentation.runOnMainSync {activity.finish()};bitmap.recycle()}
    }
}
