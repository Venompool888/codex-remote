@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ComposeView
import app.codexremote.android.presentation.projects.ProjectsController
import app.codexremote.android.ui.projects.ProjectFolder
import app.codexremote.android.ui.projects.ProjectSubmission
import app.codexremote.android.ui.projects.RemoteProjectDialogCompose
import app.codexremote.android.ui.theme.CodexTheme

class ProjectDialogDeviceTest : InstrumentationTestCase() {
    private fun launch(controller: ProjectsController): Activity {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            controller.openDialog(emptyList())
            activity.setContentView(ComposeView(activity).apply {
                setContent { CodexTheme { RemoteProjectDialogCompose(controller) } }
            })
        }
        try {
            instrumentation.awaitUiText("New remote project")
            return activity
        } catch (error: Throwable) {
            instrumentation.runOnMainSync { activity.finish() }
            throw error
        }
    }
    private fun ancestor(node: AccessibilityNodeInfo, description: String, accepts: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var current = node
        repeat(12) {
            if (accepts(current)) return current
            current = current.parent ?: error("No accessible action for $description")
        }
        error("No accessible ancestor for $description")
    }
    private fun action(label: String): AccessibilityNodeInfo {
        repeat(12) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            root?.findUiText(label)?.firstOrNull()?.let { return ancestor(it, label) { node -> node.isClickable } }
            root?.uiDescendants()?.firstOrNull { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            android.os.SystemClock.sleep(250)
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        }
        error("No reachable action for $label")
    }

    private fun input(label: String, value: String) {
        val matches = instrumentation.awaitUiText(label).findUiText(label)
        val match = matches.firstOrNull { it.text?.toString() == label } ?: matches.first()
        val node = ancestor(match, label) { it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
        (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }

    fun testFirstHostInputsSubmitAndBusyBlocksDuplicates() {
        val submitted = mutableListOf<ProjectSubmission>()
        val controller = ProjectsController(onSubmit = { submitted.add(it) })
        val activity = launch(controller)
        try {
            input("Project name", "First project")
            input("Server URL", "https://new.example")
            input("Connection name", "New host")
            input("Pairing code", "123456")
            input("/", "/project")
            action("Add project").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("Submission became busy") { controller.uiState.value.isBusy }
            instrumentation.runOnMainSync {
                assertEquals(1, submitted.size)
                assertEquals("https://new.example", submitted.single().serverUrl)
                assertEquals("123456", submitted.single().pairingCode)
                assertEquals("/project", submitted.single().workspace)
            }
            val busy = instrumentation.awaitUi("Busy submit disabled") { root ->
                root.findUiText("Adding").isNotEmpty() || nodes(root).filter { it.isEditable }.all { !it.isEnabled }
            }
            assertTrue(nodes(busy).filter { it.isEditable }.all { !it.isEnabled })
            instrumentation.runOnMainSync {
                controller.submit()
                assertEquals("Busy submission must not fire twice", 1, submitted.size)
                controller.showError("Folder unavailable")
            }
            instrumentation.awaitUiText("Folder unavailable")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testStaleBrowseCannotReplaceEditedPathAndHintsKeepFolders() {
        lateinit var complete: (List<ProjectFolder>, String?) -> Unit
        val controller = ProjectsController(onBrowse = { _, _, callback -> complete = callback })
        val activity = launch(controller)
        try {
            instrumentation.runOnMainSync {
                controller.updateServerUrl("https://fixture.invalid")
                controller.browseFolders("/")
                controller.updateFolderPath("/changed")
                complete(listOf(ProjectFolder("Stale folder", "/old")), null)
                assertTrue(controller.uiState.value.folders.isEmpty())
                controller.browseFolders("/changed")
                complete(listOf(ProjectFolder("Known project", "/known")), "Choose a known project")
            }
            val root = instrumentation.awaitUiText("Known project")
            assertTrue(root.findUiText("Stale folder").isEmpty())
            instrumentation.runOnMainSync {
                controller.updateFolderPath("/root")
                controller.browseFolders("/root")
                complete(emptyList(), "Host file operation failed (EACCES)")
            }
            instrumentation.awaitUiText("Can't open /root")
            instrumentation.awaitUiText("The remote service doesn't have permission to read this folder. Go up or choose another folder.")
            instrumentation.runOnMainSync { controller.setBusy(true) }
            instrumentation.awaitUi("All project inputs disabled while busy") { nodes(it).filter { n -> n.isEditable }.all { n -> !n.isEnabled } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testKeyboardKeepsSubmitReachable() {
        val controller = ProjectsController()
        val activity = launch(controller)
        val automation = instrumentation.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            action("Project name").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("Project keyboard is visible") {
                automation.windows.any { w -> w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            var visible = false
            repeat(12) {
                if (!visible) {
                    val root = automation.rootInActiveWindow ?: return@repeat
                    val ime = automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    val imeBounds = Rect().also { ime.getBoundsInScreen(it) }
                    val submit = root.findUiText("Add project").firstOrNull()
                    val bounds = Rect()
                    submit?.getBoundsInScreen(bounds)
                    visible = submit != null && submit.isVisibleToUser && !bounds.isEmpty && bounds.bottom <= imeBounds.top
                    if (!visible) {
                        nodes(root).firstOrNull { n -> n.isScrollable }
                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        android.os.SystemClock.sleep(150)
                    }
                }
            }
            assertTrue("Submit must remain reachable above the keyboard", visible)
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.filesDir, "qa-project-dialog-keyboard.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testFolderPrefixCompletionShowsAndOpensMatch() {
        val controller = ProjectsController(onBrowse = { _, path, done ->
            when (path) {
                "/var/lib" -> done(listOf(
                    ProjectFolder("codex-remote-public", "/var/lib/codex-remote-public"),
                    ProjectFolder("containers", "/var/lib/containers")
                ), null)
                "/var/lib/codex-remote-public" -> done(
                    listOf(ProjectFolder("workspace", "/var/lib/codex-remote-public/workspace")),
                    null
                )
                else -> done(emptyList(), null)
            }
        })
        val activity = launch(controller)
        try {
            input("Server URL", "https://fixture.invalid")
            input("/", "/var/lib/codex")
            action("codex-remote-public").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUiText("workspace")
            instrumentation.runOnMainSync {
                assertEquals("/var/lib/codex-remote-public", controller.uiState.value.folderPath)
                assertEquals("workspace", controller.uiState.value.folders.single().name)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
