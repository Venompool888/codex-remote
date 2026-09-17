@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer
class ProjectPageDeviceTest : InstrumentationTestCase() {
    fun testProjectScopeSearchAndBackPreserveTaskBoundaries() {
        val controller=SidebarController()
        instrumentation.runOnMainSync {
            controller.setThreads(listOf(RemoteThread("a","Alpha match","/a","idle",0L,false),RemoteThread("b","Beta match","/b","idle",0L,false)))
            controller.setProjectScope("host","/a");controller.updateSearchQuery("match");controller.openSidebar()
        }
        val activity=instrumentation.composeFixture {SidebarDrawer(controller)}
        try {
            val root=instrumentation.awaitUiText("Alpha match");assertTrue(root.findUiText("Beta match").isEmpty())
            instrumentation.clickUi("Back to all chats")
            instrumentation.awaitUiText("Beta match")
            assertNull(controller.uiState.value.scopedWorkspace)
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
