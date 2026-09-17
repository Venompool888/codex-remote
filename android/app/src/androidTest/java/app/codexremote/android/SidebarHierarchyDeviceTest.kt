@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer

/** Real Compose fixture; host overlap is deliberate to catch cross-connection task leaks. */
class SidebarHierarchyDeviceTest : InstrumentationTestCase() {
    fun testHierarchyPagingSearchAndSelection() {
        val host = "https://qa-sidebar.example"
        val project = RemoteProject("codex", "codex remote", "Development", host, "/codex")
        val sibling = RemoteProject("pocket", "pocketproxy", "Development", host, "/pocket")
        val otherHost = RemoteProject("other", "Other server project", "Other server", "https://other.example", "/codex")
        val titles = listOf("确认今后是否统一使用 Compose", "修复 Pixel connection 配置覆盖", "Add connections multi-delete", "评估 Compose 分支 AI 适配性", "评估 Compose 分支适配性", "Sixth hidden task")
        var selected: String? = null
        var opened: Pair<String, String>? = null
        val controller = SidebarController(onSelectThread = { selected = it.id }, onOpenProject = { server, workspace -> opened = server to workspace })
        instrumentation.runOnMainSync {
            controller.setConnectionInfo(host, "Development")
            controller.setProjects(listOf(project, sibling, otherHost), project.id)
            controller.setThreads(titles.mapIndexed { index, title -> RemoteThread("task-$index", title, "/codex", "idle", 0L, index == 1) } + RemoteThread("recent", "查询 Plasma 20 返现资格", "", "idle", 0L, false), activeThreadId = "task-0")
            controller.openSidebar()
        }
        val activity = instrumentation.composeFixture { SidebarDrawer(controller) }
        try {
            instrumentation.awaitUiText("Projects")
            instrumentation.awaitUiText(titles.first())
            instrumentation.awaitUiText(sibling.name)
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findUiText(otherHost.name).isEmpty())
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(400)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                instrumentation.targetContext.openFileOutput("qa-sidebar-hierarchy.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findUiText(titles.last()).isEmpty())
            instrumentation.clickUi("Show more")
            instrumentation.awaitUiText(titles.last())
            instrumentation.clickUi(project.name)
            instrumentation.awaitUi("Child task hidden") { it.findUiText(titles.first()).isEmpty() }
            instrumentation.clickUi(project.name)
            instrumentation.awaitUiText(titles.first())
            assertEquals(host to "/codex", opened)
            instrumentation.runOnMainSync { controller.toggleSearch(true); controller.updateSearchQuery("Sixth hidden") }
            instrumentation.awaitUiText(titles.last())
            instrumentation.clickUi(titles.last())
            assertEquals("task-5", selected)
            assertFalse(controller.uiState.value.isOpen)
            instrumentation.runOnMainSync { controller.updateSearchQuery(""); controller.toggleSearch(false); controller.openSidebar() }
            instrumentation.runOnMainSync {
                controller.setConnections(listOf(RemoteConnection("Other server", otherHost.serverUrl, listOf(otherHost))), otherHost.serverUrl, emptySet(), emptySet())
                controller.setConnectionInfo(otherHost.serverUrl, "Other server")
                controller.setProjects(listOf(project, sibling, otherHost), otherHost.id)
                controller.setThreads(listOf(RemoteThread("foreign", "Other host task", "/codex", "idle", 0L, false)))
            }
            instrumentation.awaitUiText("Other host task")
            instrumentation.awaitUi("Child task hidden") { it.findUiText(titles.first()).isEmpty() }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
