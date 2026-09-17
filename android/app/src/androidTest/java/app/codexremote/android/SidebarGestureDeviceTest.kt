@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer

/** Exercises Android pointer dispatch; callback-only tests cannot catch system-edge interception. */
class SidebarGestureDeviceTest : InstrumentationTestCase() {
    fun testHorizontalChildKeepsItsSwipe() {
        val controller = SidebarController()
        val scroll = ScrollState(300)
        val activity = instrumentation.composeFixture {
            SidebarDrawer(controller) {
                Row(Modifier.fillMaxSize().horizontalScroll(scroll)) {
                    repeat(8) { Text("Horizontal child $it", Modifier.width(300.dp)) }
                }
            }
        }
        try {
            instrumentation.awaitUiText("Horizontal child")
            val metrics = activity.resources.displayMetrics
            val before = scroll.value
            swipe(metrics.widthPixels * 0.4f, metrics.heightPixels * 0.5f,
                metrics.widthPixels * 0.8f, metrics.heightPixels * 0.5f)
            assertTrue("Horizontal scrolling child receives gesture", scroll.value < before)
            assertFalse("Child horizontal scroll must not open drawer", controller.uiState.value.isOpen)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testDisabledGesturesDoNotOpenDrawer() {
        val controller = SidebarController()
        val activity = instrumentation.composeFixture {
            SidebarDrawer(controller, edgeGesturesEnabled = false) { Text("Disabled gesture fixture") }
        }
        try {
            instrumentation.awaitUiText("Disabled gesture fixture")
            val metrics = activity.resources.displayMetrics
            swipe(metrics.widthPixels * 0.4f, metrics.heightPixels * 0.5f,
                metrics.widthPixels * 0.8f, metrics.heightPixels * 0.5f)
            assertFalse(controller.uiState.value.isOpen)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..16) {
            val event = MotionEvent.obtain(start, start + i * 20L,
                when (i) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },
                fromX + (toX - fromX) * i / 16, fromY + (toY - fromY) * i / 16, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
            SystemClock.sleep(20)
        }
        instrumentation.waitForIdleSync()
    }

    fun testContentAndEdgeSwipeOpenButVerticalScrollDoesNot() {
        var refreshes = 0
        val controller = SidebarController(onRefresh = { refreshes++ })
        val scroll = ScrollState(0)
        val activity = instrumentation.composeFixture {
            SidebarDrawer(controller) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    repeat(40) { Text("Gesture fixture $it", Modifier.height(64.dp)) }
                }
            }
        }
        try {
            instrumentation.awaitUiText("Gesture fixture")
            val metrics = activity.resources.displayMetrics
            val density = metrics.density
            val y = metrics.heightPixels * 0.5f
            val startX = 8f * density
            swipe(startX, y, startX, y - 100f * density)
            assertFalse("Vertical scroll must not open drawer", controller.uiState.value.isOpen)
            assertTrue("Edge vertical gestures must reach conversation content", scroll.value > 0)
            instrumentation.awaitUi("Vertical fling settled") { !scroll.isScrollInProgress }
            swipe(metrics.widthPixels * 0.4f, y, metrics.widthPixels * 0.8f, y)
            instrumentation.awaitUi("Content swipe opened drawer") { controller.uiState.value.isOpen }
            assertEquals(1, refreshes)
            instrumentation.runOnMainSync { controller.closeSidebar() }
            SystemClock.sleep(350)
            swipe(startX, y, 140f * density, y)
            instrumentation.awaitUi("Edge opened drawer") { controller.uiState.value.isOpen }
            assertEquals("Each opening gesture refreshes exactly once", 2, refreshes)
            instrumentation.awaitUiText("New chat")
            swipe(180f * density, y, 45f * density, y)
            instrumentation.awaitUi("Left swipe closed drawer") { !controller.uiState.value.isOpen }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
