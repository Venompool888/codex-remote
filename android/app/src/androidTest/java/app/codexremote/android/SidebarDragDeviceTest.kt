@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer
class SidebarDragDeviceTest : InstrumentationTestCase() {
    fun testSlowSwipeOpensAndClosesDrawer() {
        val controller=SidebarController()
        val activity=instrumentation.composeFixture {SidebarDrawer(controller)}
        fun drag(from:Float,to:Float) {
            val now=SystemClock.uptimeMillis();val y=activity.resources.displayMetrics.heightPixels/2f
            for(i in 0..30) {val action=if(i==0)MotionEvent.ACTION_DOWN else if(i==30)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                instrumentation.sendPointerSync(MotionEvent.obtain(now,now+i*16L,action,from+(to-from)*i/30,y,0))}
        }
        try {
            instrumentation.awaitUi("fixture") {true}
            val density=activity.resources.displayMetrics.density
            drag(8*density,160*density);instrumentation.awaitUi("drawer open") {controller.uiState.value.isOpen}
            SystemClock.sleep(350);drag(160*density,8*density);instrumentation.awaitUi("drawer closed") {!controller.uiState.value.isOpen}
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
