@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.ComposeView
import app.codexremote.android.ui.theme.CodexTheme

internal fun Instrumentation.composeFixture(content: @Composable () -> Unit): Activity {
    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    runOnMainSync { activity.setContentView(ComposeView(activity).apply { setContent { CodexTheme { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { content() } } } }) }
    return activity
}
internal fun Instrumentation.clickUi(label: String) {
    waitForIdleSync()
    if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
    var node = revealUiText(label)
    val spanned = node.text as? android.text.Spanned
    val link = spanned?.getSpans(0, spanned.length, android.text.style.ClickableSpan::class.java)?.firstOrNull()
    if (link != null) {
        runOnMainSync { link.onClick(android.view.View(targetContext)) }
        waitForIdleSync(); android.os.SystemClock.sleep(100); return
    }
    repeat(12) {
        if (node.isClickable) {
            if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) { "Cannot reveal $label" }
                android.os.SystemClock.sleep(400)
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                node = awaitUiText(label).findUiText(label).first()
                while (!node.isClickable) node = node.parent ?: error("No click action after revealing $label")
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click revealed $label" }
            }
            waitForIdleSync(); android.os.SystemClock.sleep(100); return
        }
        node = node.parent ?: error("No click action: $label")
    }
    error("No click action: $label")
}
internal fun Instrumentation.setUiText(label: String, text: String) {
    var node = awaitUiText(label).findUiText(label).first()
    repeat(12) {
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })); return
        }
        node = node.parent ?: error("No editable node: $label")
    }
    error("No editable node: $label")
}

internal fun Activity.showRequest(method: String, id: String, params: org.json.JSONObject, server: String = "qa://approval") {
    MainActivity::class.java.getDeclaredMethod("showApproval", org.json.JSONObject::class.java, RemoteClient::class.java, String::class.java)
        .apply { isAccessible = true }.invoke(this, org.json.JSONObject().put("method", method).put("requestId", id).put("params", params), null, server)
}
internal fun Activity.expireRequest(id: String, server: String = "qa://approval") {
    MainActivity::class.java.getDeclaredMethod("onMessage", String::class.java, org.json.JSONObject::class.java).apply { isAccessible = true }
        .invoke(this, server, org.json.JSONObject().put("type", "server_response_ack").put("requestId", id).put("status", "expired"))
}

/** Scroll actual form/list content before interacting with an offscreen choice. */
private fun Instrumentation.revealUiText(label: String): AccessibilityNodeInfo {
    var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    repeat(24) {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val root = uiAutomation.rootInActiveWindow
        root?.findUiText(label)?.firstOrNull()?.let { return it }
        val scroll = root?.uiDescendants()?.firstOrNull { node -> node.actionList.any { it.id == direction } }
        if (scroll == null || !scroll.performAction(direction)) direction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        android.os.SystemClock.sleep(250)
    }
    error("Text is not reachable by scrolling: $label")
}
