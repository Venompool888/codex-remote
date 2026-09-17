@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.content.Intent
import android.test.InstrumentationTestCase
import org.json.JSONObject
class UnsupportedInteractionDeviceTest : InstrumentationTestCase() {
    fun testUnsupportedFormIsTrackedAndReplayDoesNotDuplicateIt() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val params=JSONObject("""{"mode":"form","requestedSchema":{"type":"array"}}""")
        try {
            instrumentation.runOnMainSync { repeat(2) { activity.showRequest("mcpServer/elicitation/request","unsupported",params) } }
            val root=instrumentation.awaitUiText("Cancel request")
            assertEquals(1,root.uiDescendants().count { it.text?.toString()=="Cancel request" })
            assertFalse(root.uiDescendants().any { it.text?.toString()=="Allow" })
            instrumentation.runOnMainSync { activity.expireRequest("unsupported") }
            instrumentation.awaitUi("fallback dismissed") { it.findUiText("Cancel request").isEmpty() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
