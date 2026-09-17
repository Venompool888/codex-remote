@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer

class SidebarProjectNewChatDeviceTest : InstrumentationTestCase() {
    fun testSwitchingToUnpairedProjectCannotRetainOldTaskId() {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            instrumentation.runOnMainSync {
                field("connectedServerUrl").set(activity, "https://old-fixture.invalid")
                field("currentThreadId").set(activity, "old-host-task")
                val project = RemoteProject("new-project-fixture", "New project", "New host", "https://new-fixture.invalid", "/new-folder")
                MainActivity::class.java.getDeclaredMethod("activateRemoteProject", RemoteProject::class.java)
                    .apply { isAccessible = true }.invoke(activity, project)
                assertNull(field("currentThreadId").get(activity))
                assertNull(field("currentThread").get(activity))
                assertEquals(project.serverUrl, field("connectedServerUrl").get(activity))
                assertEquals(project.workspace, field("selectedWorkspace").get(activity))
                assertEquals(false, field("restoreNavigation").get(activity))
                assertEquals(project, field("pendingDraftProject").get(activity))
                MainActivity::class.java.getDeclaredMethod("finishConnected", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, project.serverUrl)
                assertNull("Reconnect consumes the explicit project target", field("pendingDraftProject").get(activity))
                assertEquals("Reconnect does not fall back to another workspace", project.workspace,
                    field("selectedWorkspace").get(activity))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testProjectPencilTargetsItsOwnHostWithoutExpanding() {
        val a = RemoteProject("a", "Project Alpha", "Host A", "https://a.invalid", "/same")
        val b = RemoteProject("b", "Project Beta", "Host B", "https://b.invalid", "/same")
        var chosen: RemoteProject? = null
        var folderClicks = 0
        val controller = SidebarController(onSelectProject = { chosen = it }, onOpenProject = { _, _ -> folderClicks++ })
        instrumentation.runOnMainSync {
            controller.setProjects(listOf(a, b), a.id)
            controller.setConnectionInfo(a.serverUrl, a.connectionName)
            controller.openSidebar()
        }
        val activity = instrumentation.composeFixture { SidebarDrawer(controller) }
        try {
            instrumentation.awaitUiText("Project Beta")
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(350)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(activity.filesDir, "qa-sidebar-new-chat.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val expanded = controller.uiState.value.expandedProjectIds
            val label = "New chat in Project Beta"
            val node = instrumentation.awaitUi(label) { root ->
                root.uiDescendants().any { it.contentDescription?.toString() == label && it.isClickable }
            }.uiDescendants().first { it.contentDescription?.toString() == label && it.isClickable }
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            assertTrue("Pencil has a 48dp target", bounds.height() >= (48 * activity.resources.displayMetrics.density).toInt() - 1)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                instrumentation.sendPointerSync(event)
                event.recycle()
            }
            instrumentation.awaitUi("New project draft callback") { chosen != null }
            assertEquals(b, chosen)
            assertEquals(0, folderClicks)
            assertEquals(expanded, controller.uiState.value.expandedProjectIds)
            assertFalse(controller.uiState.value.isOpen)
            assertEquals(b.serverUrl, controller.uiState.value.scopedServer)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
