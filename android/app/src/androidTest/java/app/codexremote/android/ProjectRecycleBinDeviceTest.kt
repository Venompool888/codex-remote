@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Context
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer

/** Local-only persistence fixture; never run on a paired physical device. */
class ProjectRecycleBinDeviceTest : InstrumentationTestCase() {
    private fun disposableOnly() {
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }
        check(avd == "Codex_Deletion_QA") { "Disposable emulator required" }
    }

    fun testSameNameProjectsRecycleIndependently() {
        disposableOnly()
        val prefs = instrumentation.targetContext.getSharedPreferences("remote_projects", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = RemoteProjectStore(instrumentation.targetContext)
        val first = RemoteProject("first", "workspace", "Host", "https://recycle.invalid", "remote-workspace://first")
        val second = first.copy(id = "second", workspace = "/workspace")
        try {
            store.save(first); store.save(second)
            store.trashProject(first.id)
            assertEquals(listOf(second), store.visibleProjects())
            assertEquals(listOf(first), store.trashedProjects())
            store.trashProject(second.id)
            store.restoreProject(first.id)
            assertEquals(listOf(first), store.visibleProjects())
            assertEquals(listOf(second), store.trashedProjects())
        } finally { prefs.edit().clear().commit() }
    }

    fun testLongPressMenuRecyclePersistenceAndRestore() {
        disposableOnly()
        val context = instrumentation.targetContext
        context.getSharedPreferences("remote_projects", Context.MODE_PRIVATE).edit().clear().commit()
        val project = RemoteProject("recycle-a", "Recycle example", "Test host", "https://recycle.invalid", "/project")
        val store = RemoteProjectStore(context)
        store.save(project)
        store.setActiveProject(project.id)
        val credentialPrefs = context.getSharedPreferences("remote_credentials", Context.MODE_PRIVATE)
        val credentialsBefore = credentialPrefs.all.toMap()
        lateinit var controller: SidebarController
        fun refresh() {
            controller.setProjects(store.visibleProjects(), store.activeProjectId())
            controller.setTrashedProjects(store.trashedProjects())
        }
        controller = SidebarController(onTrashProject = { store.trashProject(it.id); refresh() }, onRestoreProject = { store.restoreProject(it.id); refresh() })
        instrumentation.runOnMainSync {
            controller.setConnectionInfo(project.serverUrl, project.connectionName)
            refresh()
            controller.setThreads(listOf(RemoteThread("task", "Retained host task", project.workspace, "idle", 0L, false)))
            controller.openSidebar()
        }
        val activity = instrumentation.composeFixture { SidebarDrawer(controller) }
        try {
            instrumentation.awaitUiText(project.name)
            var node = instrumentation.uiAutomation.rootInActiveWindow.findUiText(project.name).first()
            while (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) node = node.parent ?: error("Missing project long press")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            instrumentation.awaitUiText("Move to recycle bin")
            assertEquals(listOf(project), store.visibleProjects()) // Long press itself must not mutate.
            android.os.SystemClock.sleep(250)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                context.openFileOutput("qa-project-long-press.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.clickUi("Move to recycle bin")
            instrumentation.awaitUi("project removed") { store.visibleProjects().isEmpty() }
            instrumentation.awaitUi("child task hidden") { it.findUiText("Retained host task").isEmpty() }
            assertEquals(listOf(project), RemoteProjectStore(context).trashedProjects())
            assertEquals(project.id, store.activeProjectId())
            assertEquals(1, store.connections().size) // Recycling the last project does not delete its connection.
            store.mergeDiscovered(project.serverUrl, project.connectionName, listOf(project.workspace))
            assertTrue(store.visibleProjects().isEmpty()) // Host refresh cannot resurrect it.
            assertEquals(listOf(project), store.list())
            instrumentation.runOnMainSync { controller.toggleSearch(true); controller.updateSearchQuery("no matching project") }
            instrumentation.clickUi("Recycle bin")
            instrumentation.awaitUiText("Restore")
            instrumentation.clickUi("Restore")
            instrumentation.awaitUi("project restored") { store.visibleProjects() == listOf(project) }
            assertTrue(RemoteProjectStore(context).trashedProjects().isEmpty())
            assertEquals(credentialsBefore, credentialPrefs.all)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            context.getSharedPreferences("remote_projects", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
