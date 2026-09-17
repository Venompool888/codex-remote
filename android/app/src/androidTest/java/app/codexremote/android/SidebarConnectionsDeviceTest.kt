@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.InputDevice
import android.view.MotionEvent
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer

/** Disposable Compose fixture: real chip touch dispatch, host isolation and drawer sizing. */
class SidebarConnectionsDeviceTest : InstrumentationTestCase() {
    fun testBrowsingOtherHostKeepsConversationAndNewChatUsesSelectedHost() {
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val a = RemoteProject("route-a", "Mac folder", "Mac", "https://route-a.invalid", "/mac")
        val b = RemoteProject("route-b", "Remote host folder", "Remote host", "https://route-b.invalid", "/secondary")
        try {
            instrumentation.runOnMainSync {
                val store = RemoteProjectStore(activity)
                store.save(a); store.save(b)
                field("remoteProjects").set(activity, listOf(a, b))
                field("connectedServerUrl").set(activity, a.serverUrl)
                field("sidebarConnectionUrl").set(activity, a.serverUrl)
                field("activeProjectId").set(activity, a.id)
                field("selectedWorkspace").set(activity, a.workspace)
                field("currentThreadId").set(activity, "old-mac-task")
                MainActivity::class.java.getDeclaredMethod("selectSidebarConnection", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, b.serverUrl)
                assertEquals(a.serverUrl, field("connectedServerUrl").get(activity))
                assertEquals("old-mac-task", field("currentThreadId").get(activity))
                assertEquals(b.serverUrl, field("sidebarConnectionUrl").get(activity))
                val opened = MainActivity::class.java.getDeclaredMethod("activateSidebarConnection", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, b.workspace)
                assertEquals(false, opened)
                assertEquals("Offline sidebar selection cannot retarget a live conversation", a.serverUrl, field("connectedServerUrl").get(activity))
                MainActivity::class.java.getDeclaredMethod("newThreadFromSidebar")
                    .apply { isAccessible = true }.invoke(activity)
                assertEquals(b, field("pendingDraftProject").get(activity))
                assertEquals(b.workspace, field("selectedWorkspace").get(activity))
                assertNull(field("currentThreadId").get(activity))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testConnectionSwitchAndHorizontalOverflowKeepDrawerOpen() {
        val names = listOf("Remote host", "Mac", "Lab", "Staging", "Backup")
        val projects = names.map { RemoteProject(it, "$it workspace", it, "https://${it.lowercase()}.example", "/same") }
        val connections = projects.map { RemoteConnection(it.connectionName, it.serverUrl, listOf(it)) }
        lateinit var controller: SidebarController
        var selected = projects.first().serverUrl
        fun load(server: String) {
            controller.setConnections(connections, server, connections.map { it.serverUrl }.toSet(), emptySet())
            controller.setProjects(projects, projects.first { it.serverUrl == server }.id)
            controller.setThreads(listOf(RemoteThread("same-task-id", "${projects.first { it.serverUrl == server }.name} task", "/same", "idle", 0L, false)))
        }
        controller = SidebarController(onSelectConnection = { selected = it; load(it) })
        instrumentation.runOnMainSync { load(selected); controller.openSidebar() }
        val activity = instrumentation.composeFixture { SidebarDrawer(controller) }
        try {
            instrumentation.awaitUiText("Remote host workspace")
            SystemClock.sleep(400) // Let the drawer entrance animation settle before touch injection.
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findUiText("Mac workspace").isEmpty())
            // The visible chip is 29dp, but its outer 48dp padding must still respond.
            clickConnection("Mac", inTouchPadding = true)
            instrumentation.awaitUiText("Mac workspace task")
            assertEquals(projects[1].serverUrl, selected)
            assertTrue(controller.uiState.value.isOpen)
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findUiText("Remote host workspace").isEmpty())
            val chip = connectionNode("Mac")
            val bounds = Rect().also(chip::getBoundsInScreen)
            val width = activity.resources.displayMetrics.widthPixels
            swipe(width * 0.72f, bounds.exactCenterY(), width * 0.12f, bounds.exactCenterY())
            assertTrue("Connection-strip swipe must not close drawer", controller.uiState.value.isOpen)
            clickConnection("Backup")
            instrumentation.awaitUiText("Backup workspace task")
            assertEquals(projects.last().serverUrl, selected)
            assertTrue(controller.uiState.value.isOpen)
            val title = instrumentation.awaitUiText("Backup workspace").findUiText("Backup workspace").first()
            val titleBounds = Rect().also(title::getBoundsInScreen)
            assertTrue("Drawer content leaves main-screen reveal", titleBounds.right < width * 0.85f)
            // The retained main-screen reveal closes the drawer when tapped.
            tap(width * 0.93f, activity.resources.displayMetrics.heightPixels * 0.5f)
            instrumentation.awaitUi("Scrim closes drawer") { !controller.uiState.value.isOpen }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun connectionNode(name: String): android.view.accessibility.AccessibilityNodeInfo {
        val prefix = "$name (https://${name.lowercase()}.example)"
        return instrumentation.awaitUi("Connection $name visible") { root ->
            root.uiDescendants().any { it.contentDescription?.toString()?.startsWith(prefix) == true && it.isVisibleToUser }
        }.uiDescendants().first { it.contentDescription?.toString()?.startsWith(prefix) == true && it.isVisibleToUser }
    }

    private fun clickConnection(name: String, inTouchPadding: Boolean = false) {
        val node = connectionNode(name)
        val bounds = Rect().also(node::getBoundsInScreen)
        tap(bounds.exactCenterX(), if (inTouchPadding) bounds.top + 2f else bounds.exactCenterY())
    }

    private fun tap(x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
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
}
