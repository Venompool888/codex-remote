@file:Suppress("DEPRECATION")

package app.codexremote.android

import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer

class SidebarRunningIndicatorDeviceTest : InstrumentationTestCase() {
    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { index ->
            node.getChild(index)?.let(::descendants).orEmpty()
        }

    private fun nodes(): List<AccessibilityNodeInfo> =
        instrumentation.uiAutomation.rootInActiveWindow?.let(::descendants).orEmpty()

    fun testRunningChildMovesBetweenExpandedThreadAndCollapsedProject() {
        val host = "https://running-sidebar.example"
        val project = RemoteProject("project", "Running project", "Host", host, "/project")
        val running = RemoteThread("running", "Background AI task", project.workspace, "active", 1L, true)
        val idle = RemoteThread("idle", "Idle task", project.workspace, "idle", 0L, false)
        val controller = SidebarController()
        instrumentation.runOnMainSync {
            controller.setConnectionInfo(host, "Host")
            controller.setProjects(listOf(project), project.id)
            controller.setThreads(listOf(running, idle), activeThreadId = idle.id)
            controller.openSidebar()
        }
        val activity = instrumentation.composeFixture { SidebarDrawer(controller) }
        try {
            instrumentation.awaitUiText(running.title)
            instrumentation.awaitUi("running thread state") {
                nodes().count { it.contentDescription?.toString() == "AI is working" } == 1
            }
            val expandedProject = nodes().first { it.contentDescription?.toString() == "Project ${project.name}" }
            assertNull("Project row itself is not a duplicate progress node", expandedProject.rangeInfo)

            instrumentation.clickUi(project.name)
            instrumentation.awaitUi("running child hidden") { it.findUiText(running.title).isEmpty() }
            instrumentation.awaitUi("collapsed project aggregates running state") {
                nodes().count { it.contentDescription?.toString() == "AI is working" } == 1
            }

            instrumentation.runOnMainSync {
                controller.setThreads(listOf(running.copy(status = "completed", isRunning = false), idle), activeThreadId = idle.id)
            }
            instrumentation.awaitUi("idle collapsed project clears running state") {
                nodes().none { it.contentDescription?.toString() == "AI is working" }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
