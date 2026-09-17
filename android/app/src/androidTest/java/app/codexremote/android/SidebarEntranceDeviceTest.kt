@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer
class SidebarEntranceDeviceTest : InstrumentationTestCase() {
    fun testRefreshingClosedSidebarDoesNotOpenIt() {
        val controller=SidebarController()
        val activity=instrumentation.composeFixture {SidebarDrawer(controller)}
        try {instrumentation.runOnMainSync {controller.setThreads(listOf(RemoteThread("a","Refreshed task","/a","idle",0L,false)));assertFalse(controller.uiState.value.isOpen);controller.openSidebar()}
            instrumentation.awaitUiText("Refreshed task")
            instrumentation.runOnMainSync {controller.closeSidebar();controller.setThreads(emptyList());assertFalse(controller.uiState.value.isOpen)}
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
