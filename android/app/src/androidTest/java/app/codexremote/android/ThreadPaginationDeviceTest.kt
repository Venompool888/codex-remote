@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer
class ThreadPaginationDeviceTest : InstrumentationTestCase() {
    fun testLoadMoreUsesExplicitPagingAction() {
        var calls=0;val controller=SidebarController(onLoadMore={calls++})
        instrumentation.runOnMainSync {controller.setThreads(listOf(RemoteThread("a","First task","/a","idle",0L,false)));controller.setPaging(true,false);controller.openSidebar()}
        val activity=instrumentation.composeFixture {SidebarDrawer(controller)}
        try {instrumentation.clickUi("Load more");instrumentation.awaitUi("paging requested") {calls==1}
            instrumentation.runOnMainSync {controller.setPaging(true,true);controller.loadMore();assertEquals(1,calls)}
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
