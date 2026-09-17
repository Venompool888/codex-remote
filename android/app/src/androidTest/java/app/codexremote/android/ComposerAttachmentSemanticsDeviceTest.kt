@file:Suppress("DEPRECATION")

package app.codexremote.android

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ComposeView
import app.codexremote.android.compose.AttachmentItemUiState
import app.codexremote.android.compose.AttachmentUploadState
import app.codexremote.android.compose.ComposerAttachmentStrip
import app.codexremote.android.compose.FileAttachmentUiState
import app.codexremote.android.compose.ImageAttachmentUiState
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production composables through Android's accessibility bridge. */
class ComposerAttachmentSemanticsDeviceTest : InstrumentationTestCase() {
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        fun descend(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { index ->
                node.getChild(index)?.let(::descend) ?: emptyList()
            }
        return descend(root)
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            nodes().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected accessibility node missing: " + describeNodes())
    }

    private fun describeNodes() = nodes().joinToString("\n") { "desc=${it.contentDescription} text=${it.text} enabled=${it.isEnabled} clickable=${it.isClickable} actions=${it.actionList}" }

    // Compose exposes a content-description child separately from its clickable parent.
    private fun cardAction(label: String): AccessibilityNodeInfo {
        var node = awaitNode { it.contentDescription?.toString() == label }
        while (!node.isClickable) node = node.parent ?: error("Card has no action parent")
        return node
    }

    fun testLoadingImageBlocksOpenButAllowsRemoval() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val clicks = AtomicInteger()
        val removes = AtomicInteger()
        val items = mutableStateListOf<AttachmentItemUiState>(
            ImageAttachmentUiState("loading", "loading.png", isLoadingPreview = true)
        )
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerAttachmentStrip(items, onCardClick = { clicks.incrementAndGet() },
                            onRemoveClick = { item -> removes.incrementAndGet(); items.removeAll { it.localId == item.localId } })
                    }
                })
            }
            val card = cardAction("loading.png")
            assertFalse("Loading image must expose disabled state: " + describeNodes(), card.isEnabled)
            card.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(150)
            assertEquals("Loading image cannot open a second preview", 0, clicks.get())
            val remove = awaitNode { node -> node.actionList.any { it.label?.toString() == "Remove loading.png" } }
            assertTrue("Removal must remain available during preview loading", remove.isEnabled)
            val bounds = Rect().also(remove::getBoundsInScreen)
            val target = (48 * activity.resources.displayMetrics.density).toInt()
            assertTrue("Remove touch target width", bounds.width() >= target)
            assertTrue("Remove touch target height", bounds.height() >= target)
            assertTrue(remove.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (removes.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals(1, removes.get())
            val removalDeadline = SystemClock.uptimeMillis() + 3_000
            while (nodes().any { it.contentDescription?.toString() == "loading.png" } &&
                SystemClock.uptimeMillis() < removalDeadline) SystemClock.sleep(100)
            assertFalse("Removed image must leave accessibility tree", nodes().any { it.contentDescription?.toString() == "loading.png" })
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testFileRetryAndStableRemovalAfterPrecedingItemRemoved() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val clicks = AtomicInteger()
        val items = mutableStateListOf<AttachmentItemUiState>(
            ImageAttachmentUiState("first", "first.png"),
            FileAttachmentUiState("pdf", "report.pdf", metadataText = "PDF · 12 KB",
                uploadState = AttachmentUploadState.Failed())
        )
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerAttachmentStrip(items, onCardClick = { if (it.localId == "pdf") clicks.incrementAndGet() },
                            onRemoveClick = { item -> items.removeAll { it.localId == item.localId } })
                    }
                })
            }
            awaitNode { it.contentDescription?.toString() == "first.png" }
            instrumentation.runOnMainSync { items.removeAll { it.localId == "first" } }
            val reflowDeadline = SystemClock.uptimeMillis() + 3_000
            while (nodes().any { it.contentDescription?.toString() == "first.png" } &&
                SystemClock.uptimeMillis() < reflowDeadline) SystemClock.sleep(100)
            assertFalse("Preceding image must leave the semantic tree", nodes().any { it.contentDescription?.toString() == "first.png" })
            val pdf = cardAction("report.pdf")
            assertTrue(pdf.isEnabled)
            assertTrue(pdf.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (clicks.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertEquals("Retry targets the remaining PDF", 1, clicks.get())
            val remove = awaitNode { node -> node.actionList.any { it.label?.toString() == "Remove report.pdf" } }
            assertTrue(remove.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(250)
            instrumentation.runOnMainSync { assertTrue("Removal still targets PDF after reindex", items.isEmpty()) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
