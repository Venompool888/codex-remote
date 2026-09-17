@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.content.Intent
import android.test.InstrumentationTestCase
import org.json.JSONObject
import org.json.JSONArray
class FileReviewDeviceTest : InstrumentationTestCase() {
    fun testCompletePatchAndMissingPatchDecisions() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            for(available in listOf(true,false)) {
                val params=JSONObject()
                if(available) params.put("fileChangeReview",JSONObject().put("status","available").put("changes",JSONArray().put(JSONObject().put("path","src/report.txt").put("kind","update").put("diff","-old\n+FILE_REVIEW_4831\n"+(1..60).joinToString("\n"){" context line $it"}))))
                instrumentation.runOnMainSync { activity.showRequest("item/fileChange/requestApproval","$available",params) }
                val root=instrumentation.awaitUiText(if(available) "FILE_REVIEW_4831" else "File patch details are unavailable")
                assertEquals(available,root.uiDescendants().any { it.text?.toString()=="Allow" })
                val detail=root.uiDescendants().mapNotNull { it.text?.toString() }.joinToString("\n")
                if(available) {assertTrue(detail.contains("src/report.txt"));assertTrue(detail.contains("-old\n+FILE_REVIEW_4831"));assertTrue(detail.contains("context line 60"))}
                else assertTrue(detail.contains("File patch details are unavailable"))
                instrumentation.runOnMainSync { activity.expireRequest("$available") }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
