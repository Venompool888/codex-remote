@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import app.codexremote.android.ui.conversation.SubagentDirectory

/** Synthetic subagent ancestry UI regressions; execute only on a disposable emulator. */
class SubagentAncestryRegressionDeviceTest : InstrumentationTestCase() {
    fun testFilteredChildNamesHiddenParentAndPreservesSelection() {
        var inspected: String? = null
        val activity = instrumentation.composeFixture {
            SubagentDirectory(
                entries = listOf(
                    SubagentDirectoryEntry("parent", "Parent worker", "completed"),
                    SubagentDirectoryEntry("child", "Child worker", "running", parentId = "parent"),
                ),
                loading = false,
                error = null,
                canLoadMore = false,
                onRefresh = {},
                onLoadMore = {},
                onInspect = { inspected = it },
                modifier = Modifier.safeDrawingPadding(),
            )
        }
        try {
            instrumentation.awaitUi("all filter omits redundant parent label") { root ->
                root.findUiText("Parent worker").isNotEmpty() &&
                    root.findUiText("Child worker").isNotEmpty() &&
                    root.uiDescendants().none { it.text?.toString() == "From: Parent worker" }
            }

            instrumentation.clickUi("Running (1)")
            instrumentation.awaitUi("filtered child names its hidden parent") { root ->
                root.uiDescendants().any { it.text?.toString() == "From: Parent worker" }
            }
            instrumentation.clickUi("Child worker")
            instrumentation.runOnMainSync { assertEquals("child", inspected) }

            instrumentation.clickUi("All (2)")
            instrumentation.awaitUi("all filter removes parent label") { root ->
                root.findUiText("Parent worker").isNotEmpty() &&
                    root.uiDescendants().none { it.text?.toString() == "From: Parent worker" }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
