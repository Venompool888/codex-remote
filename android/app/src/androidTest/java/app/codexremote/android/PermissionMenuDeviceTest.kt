@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.io.File
import app.codexremote.android.presentation.composer.ComposerController
import app.codexremote.android.presentation.composer.ComposerOption
import app.codexremote.android.ui.composer.ComposerSection

/** Disposable-emulator fixture: no live host, credentials, or user drafts. */
class PermissionMenuDeviceTest : InstrumentationTestCase() {
    private fun nodes() = instrumentation.uiAutomation.windows.flatMap { it.root?.uiDescendants().orEmpty() }

    private fun menuText(label: String): android.view.accessibility.AccessibilityNodeInfo {
        val started = android.os.SystemClock.uptimeMillis()
        val deadline = started + 10000
        var direction = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var attempts = 0
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.clearCache()
            nodes().firstOrNull { it.text?.toString() == label }?.let { return it }
            if (android.os.SystemClock.uptimeMillis() - started > 700) {
                val scroll = nodes().firstOrNull { it.isScrollable }
                if (scroll?.performAction(direction) != true || ++attempts % 5 == 0) {
                    direction = if (direction == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            }
            android.os.SystemClock.sleep(200)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "qa-permission-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        error("Menu option not reachable: $label; visible labels: ${nodes().mapNotNull { it.text }.joinToString()}")
    }

    private fun clickMenu(label: String) {
        var node = menuText(label)
        while (!node.isClickable) node = node.parent ?: error("No click target for $label")
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    fun testSelectionPreservesDraftAndFullAccessRequiresConfirmation() {
        val originalServiceInfo = instrumentation.uiAutomation.serviceInfo
        val serviceInfo = instrumentation.uiAutomation.serviceInfo
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = serviceInfo
        var selected = ""
        val controller = ComposerController(onPermissionModeChanged = { selected = it })
        instrumentation.runOnMainSync {
            controller.updateText("Draft stays here")
            controller.setPermissionOptions(listOf(
                ComposerOption("workspace", "Default permissions", "Runs commands in a sandbox"),
                ComposerOption("guardian", "Auto-review", "Unavailable on this host", false),
                ComposerOption("read-only", "Read only", "Requires approval to edit files or run commands"),
                ComposerOption("full-access", "Full access", "Full computer access (elevated risk)"),
                ComposerOption("custom", "Custom (config.toml)", "Codex uses the permission defined in config.toml")
            ), "workspace")
        }
        val activity = instrumentation.composeFixture {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { ComposerSection(controller) }
        }
        try {
            val editor = instrumentation.awaitUi("editor") { root -> root.uiDescendants().any { it.isEditable } }
                .uiDescendants().first { it.isEditable }
            editor.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(500)
            instrumentation.runOnMainSync { controller.togglePermissionMenu(true) }
            menuText("Default permissions")
            android.os.SystemClock.sleep(700)
            val menuRoot = instrumentation.uiAutomation.windows.mapNotNull { it.root }.first { root ->
                root.uiDescendants().any { it.text?.toString() == "Default permissions" }
            }
            val scrollBounds = android.graphics.Rect().also { bounds -> menuRoot.getBoundsInScreen(bounds) }
            val editorBounds = android.graphics.Rect().also { bounds -> nodes().first { it.isEditable }.getBoundsInScreen(bounds) }
            assertTrue("Menu remains above the composer and keyboard", scrollBounds.bottom <= editorBounds.top)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(activity.filesDir, "qa-permission-menu.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            clickMenu("Read only")
            instrumentation.awaitUi("read-only callback") { selected == "read-only" }
            assertFalse(controller.uiState.value.showPermissionMenu)
            assertEquals("Draft stays here", controller.uiState.value.text)
            instrumentation.runOnMainSync { controller.togglePermissionMenu(true) }
            clickMenu("Full access")
            instrumentation.awaitUi("full-access confirmation") { controller.uiState.value.showFullAccessWarning }
            assertEquals("read-only", selected)
            instrumentation.clickUi("Cancel")
            assertEquals("read-only", selected)
            instrumentation.runOnMainSync { controller.togglePermissionMenu(true) }
            clickMenu("Custom (config.toml)")
            instrumentation.awaitUi("custom callback") { selected == "custom" }
            assertEquals("Draft stays here", controller.uiState.value.text)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.uiAutomation.serviceInfo = originalServiceInfo
        }
    }
}
