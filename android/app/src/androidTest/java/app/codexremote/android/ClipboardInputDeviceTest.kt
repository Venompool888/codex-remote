package app.codexremote.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.test.InstrumentationTestCase

/** QA-only Unicode entry into the foreground release app; never included in production. */
class ClipboardInputDeviceTest : InstrumentationTestCase() {
    fun testPasteFixture() {
        val file = java.io.File(instrumentation.targetContext.filesDir, "qa-paste.txt")
        val value = file.readText()
        instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        try {
            instrumentation.runOnMainSync {
                val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("QA input", value))
            }
        } finally { file.delete() }
    }
}
