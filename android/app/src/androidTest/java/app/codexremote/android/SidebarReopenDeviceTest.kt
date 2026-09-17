@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer
class SidebarReopenDeviceTest : InstrumentationTestCase() {
    fun testRapidReopenDoesNotRemoveNewDrawer() {
        val controller=SidebarController()
        val activity=instrumentation.composeFixture {SidebarDrawer(controller)}
        try {
            instrumentation.runOnMainSync {controller.setThreads(listOf(RemoteThread("a","Reopen proof","/a","idle",0L,false)));controller.openSidebar()}
            instrumentation.awaitUiText("Reopen proof")
            instrumentation.runOnMainSync {controller.closeSidebar();controller.openSidebar()}
            instrumentation.awaitUiText("Reopen proof");assertTrue(controller.uiState.value.isOpen)
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
