@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.codexremote.android.presentation.artifacts.ArtifactsController
import app.codexremote.android.ui.artifacts.ImageViewerDialog

class ImageViewerDeviceTest : InstrumentationTestCase() {
    fun testFullscreenZoomAndClose() {
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val controller = ArtifactsController()
        instrumentation.runOnMainSync { controller.openImageViewer(bitmap, "image-viewer-proof.png") }
        val activity = instrumentation.composeFixture { ImageViewerDialog(controller) }
        try {
            val root = instrumentation.awaitUi("image") { it.uiDescendants().any { node -> node.contentDescription == "image-viewer-proof.png" } }
            val image = root.uiDescendants().first { it.contentDescription == "image-viewer-proof.png" }
            val bounds = Rect().also(image::getBoundsInScreen)
            repeat(2) {
                val now = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0))
                instrumentation.sendPointerSync(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0))
                SystemClock.sleep(70)
            }
            instrumentation.awaitUi("300 percent zoom") { it.uiDescendants().any { node -> node.stateDescription?.toString() == "300% zoom" } }
            val close = instrumentation.awaitUi("close image") { it.uiDescendants().any { node -> node.contentDescription == "Close image" } }
                .uiDescendants().first { it.contentDescription == "Close image" }
            var button = close
            repeat(8) { if (!button.isClickable) button = button.parent ?: button }
            val touch = Rect().also(button::getBoundsInScreen)
            assertTrue(touch.width() >= (48 * activity.resources.displayMetrics.density).toInt())
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.awaitUi("viewer dismissed") { !controller.uiState.value.isViewerOpen }
        } finally { instrumentation.runOnMainSync { activity.finish() }; bitmap.recycle() }
    }
}
