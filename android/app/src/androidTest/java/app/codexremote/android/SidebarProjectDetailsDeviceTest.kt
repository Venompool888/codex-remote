@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import app.codexremote.android.presentation.sidebar.SidebarController
import app.codexremote.android.ui.sidebar.SidebarDrawer

class SidebarProjectDetailsDeviceTest : InstrumentationTestCase() {
    fun testDetailsBelongToLongPressedProjectNotActiveConnection() {
        val other = RemoteProject("other", "Other workspace", "Other connection", "https://other.example", "/srv/projects/other workspace/中文目录")
        val active = RemoteProject("active", "Active workspace", "Active connection", "https://active.example", "/srv/active")
        var trashed = false
        val controller = SidebarController(onTrashProject = { trashed = true })
        instrumentation.runOnMainSync {
            controller.setProjects(listOf(active, other), active.id)
            controller.setConnectionInfo(active.serverUrl, active.connectionName)
            controller.openSidebar()
        }
        val activity = instrumentation.composeFixture { SidebarDrawer(controller) }
        try {
            var node = instrumentation.awaitUiText(other.name).findUiText(other.name).first()
            while (!node.isLongClickable) node = node.parent ?: error("No long press target")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            instrumentation.awaitUiText("Move to recycle bin")
            instrumentation.clickUi("Details")
            instrumentation.awaitUiText(other.connectionName)
            instrumentation.awaitUiText(other.serverUrl)
            instrumentation.awaitUiText(other.workspace)
            instrumentation.clickUi("Copy path")
            var copied: String? = null
            instrumentation.runOnMainSync {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals("Copy must preserve the complete remote workspace", other.workspace, copied)
            assertFalse("Reading details must not recycle a project", trashed)
            assertEquals("Reading details must not navigate to another project", active.id, controller.uiState.value.activeProjectId)
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                instrumentation.targetContext.openFileOutput("qa-project-details.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
