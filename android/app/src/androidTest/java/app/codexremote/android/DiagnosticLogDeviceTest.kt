@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.app.Activity
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import app.codexremote.android.ui.diagnostics.DiagnosticLogDialog
import app.codexremote.android.ui.theme.CodexTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run on a disposable emulator only: clears this app's diagnostic files. */
class DiagnosticLogDeviceTest : InstrumentationTestCase() {
    private fun click(label: String) {
        var action: AccessibilityNodeInfo? = null
        instrumentation.awaitUi("clickable $label") { root ->
            var node = root.uiDescendants().firstOrNull { it.text?.toString() == label }
            repeat(12) {
                if (node?.isClickable == true) { action = node; return@awaitUi true }
                node = node?.parent
            }
            false
        }
        assertTrue(action!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    fun testErrorTextAndShareRefreshCallbacks() {
        var shared = 0
        var refreshed = 0
        var cleared = 0
        val activity: Activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent { CodexTheme {
                        DiagnosticLogDialog(
                            report = "2026-09-14T07:01:07Z rpc.rpc_error\n  method=thread/start\n  failed to load configuration: Operation not permitted (os error 1)",
                            onRefresh = { refreshed++ }, onShare = { shared++ }, onClear = { cleared++ }, onDismiss = {}
                        )
                    } }
                })
            }
            instrumentation.awaitUiText("Operation not permitted (os error 1)")
            File(activity.filesDir, "qa-diagnostic-log.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            click("Refresh")
            click("Share")
            click("Clear")
            instrumentation.awaitUiText("Clear diagnostic log?")
            click("Cancel")
            instrumentation.awaitUi("log visible after cancelling clear") {
                it.findUiText("Refresh").isNotEmpty() && it.findUiText("Clear diagnostic log?").isEmpty()
            }
            assertEquals(0, cleared)
            click("Clear")
            instrumentation.awaitUiText("Clear diagnostic log?")
            click("Clear")
            instrumentation.runOnMainSync {
                assertEquals(1, refreshed)
                assertEquals(1, shared)
                assertEquals(1, cleared)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testExportIsReadableThroughProviderAndClearRemovesExportAndLogs() {
        val context = instrumentation.targetContext
        val logs = DiagnosticLogs.get(context)
        val cleared = CountDownLatch(1)
        logs.clear { assertTrue(it); cleared.countDown() }
        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        logs.record("rpc.rpc_error", "https://mac.example", "method=thread/start\nfailed to load configuration: Operation not permitted (os error 1)\nBearer diagnostic-test-token")
        var exported: File? = null
        val ready = CountDownLatch(1)
        logs.export { exported = it; ready.countDown() }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val file = exported ?: error("Export failed")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.artifacts", file)
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(text.contains("Operation not permitted (os error 1)"))
        assertFalse(text.contains("diagnostic-test-token"))
        val done = CountDownLatch(1)
        logs.clear { assertTrue(it); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(file.exists())
        assertEquals("", DiagnosticLogStore(File(context.filesDir, "diagnostics")).read())
    }
}
