@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.app.Instrumentation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Wait for an actual accessible frame; waitForIdleSync alone does not await popup/Compose rendering. */
internal fun Instrumentation.awaitUi(
    description: String,
    timeoutMs: Long = 5_000,
    predicate: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    do {
        // Compose updates virtual nodes in place; do not keep a cached enabled
        // state while waiting for a recomposition or transition to complete.
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val root = uiAutomation.rootInActiveWindow
        if (root != null && predicate(root)) return root
        SystemClock.sleep(50)
    } while (SystemClock.uptimeMillis() < deadline)
    throw AssertionError("Timed out waiting for UI: $description")
}

internal fun Instrumentation.awaitUiText(text: String): AccessibilityNodeInfo =
    awaitUi(text) { it.findUiText(text).isNotEmpty() }

/** Traverse virtual Compose nodes too; the platform text-search shortcut can omit them. */
internal fun AccessibilityNodeInfo.uiDescendants(): List<AccessibilityNodeInfo> = listOf(this) +
    (0 until childCount).flatMap { getChild(it)?.uiDescendants().orEmpty() }

internal fun AccessibilityNodeInfo.findUiText(value: String): List<AccessibilityNodeInfo> =
    uiDescendants().filter { it.text?.toString()?.contains(value) == true }
