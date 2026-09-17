@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import app.codexremote.android.presentation.artifacts.ArtifactsController
import app.codexremote.android.ui.artifacts.ArtifactsDialog
import app.codexremote.android.ui.theme.CodexTheme

/** Verifies the Compose presentation separately from the source-bound downloader tests. */
class ArtifactPresentationDeviceTest : InstrumentationTestCase() {
    private fun launch(controller: ArtifactsController): Activity {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            activity.setContentView(ComposeView(activity).apply {
                setContent { CodexTheme { ArtifactsDialog(controller) } }
            })
        }
        return activity
    }
    private fun action(label: String): AccessibilityNodeInfo {
        val root = instrumentation.awaitUiText(label)
        var node = root.findUiText(label).first()
        repeat(12) {
            if (node.isClickable) return node
            node = node.parent ?: error("No button for $label")
        }
        error("No clickable ancestor for $label")
    }

    fun testProgressCancelAndFailureRetryActions() {
        val controller = ArtifactsController()
        val activity = launch(controller)
        var cancelled = 0
        var retried = 0
        try {
            instrumentation.runOnMainSync {
                controller.onDownloadEvent(ArtifactDownloadEvent.Started("one", "report.txt") { cancelled++ })
                controller.onDownloadEvent(ArtifactDownloadEvent.Progress("one", 42))
            }
            instrumentation.awaitUiText("42%")
            action("Cancel").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("Cancellation delivered") { cancelled == 1 }
            instrumentation.runOnMainSync {
                controller.onDownloadEvent(ArtifactDownloadEvent.Started("two", "report.txt") {})
                controller.onDownloadEvent(ArtifactDownloadEvent.Failed("two") { retried++ })
            }
            action("Retry").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("Retry delivered") { retried == 1 }
            action("Close").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("Failed download dismissed") { controller.uiState.value.downloadProgress == null }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testVerifiedDownloadActionsRemainVisibleAndUseProvidedCallbacks() {
        val controller = ArtifactsController()
        val activity = launch(controller)
        var opened = 0
        var shared = 0
        try {
            instrumentation.runOnMainSync {
                controller.onDownloadEvent(ArtifactDownloadEvent.Started("verified", "report.txt") {})
                controller.onDownloadEvent(ArtifactDownloadEvent.Completed("verified", "report.txt", { opened++ }, { shared++ }))
            }
            val buttons = listOf("Share", "Open", "Done").map(::action)
            val screen = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            val bounds = buttons.map { button ->
                Rect().also {
                    button.getBoundsInScreen(it)
                    assertTrue("Download action is visible", button.isVisibleToUser)
                    assertFalse("Download action has nonempty bounds", it.isEmpty)
                    assertTrue("Download action stays inside the dialog", screen.contains(it))
                }
            }
            bounds.forEachIndexed { index, rect -> bounds.drop(index + 1).forEach {
                assertFalse("Download action touch targets must not overlap", Rect.intersects(rect, it))
            } }
            buttons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("Share action delivered") { shared == 1 }
            action("Open").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("Open action delivered") { opened == 1 }
            assertNull(controller.uiState.value.downloadProgress)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
