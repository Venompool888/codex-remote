@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.content.Intent
import android.graphics.Rect
import android.test.InstrumentationTestCase
import org.json.JSONObject
import org.json.JSONArray
class ApprovalDetailsDeviceTest : InstrumentationTestCase() {
    private fun checkRequest(method: String, params: JSONObject, allow: Boolean, negative: String) {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync { activity.showRequest(method,"request",params) }
            val root=instrumentation.awaitUiText(negative)
            assertEquals(allow,root.uiDescendants().any { it.text?.toString()=="Allow" })
            val node=root.findUiText(negative).first(); val bounds=Rect().also(node::getBoundsInScreen)
            assertTrue(bounds.width()>0 && bounds.height()>0)
            if(params.optString("command").contains("QA_LINE_100")) {
                assertTrue(root.uiDescendants().any { it.isScrollable })
                assertTrue(root.uiDescendants().any { it.text?.contains("QA_LINE_100")==true })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testRestrictedApprovalDoesNotOfferOneTimeAllow() = checkRequest("item/commandExecution/requestApproval",JSONObject().put("command","printf QA").put("availableDecisions",JSONArray().put("acceptForSession").put("cancel")),false,"Cancel task")
    fun testSessionWriteRootIsNotOfferedAsOneTimeAllow() = checkRequest("item/fileChange/requestApproval",JSONObject().put("grantRoot","/workspace/export"),false,"Deny")
    fun testLongApprovalScrollsWithoutHidingDecisionButtons() = checkRequest("item/commandExecution/requestApproval",JSONObject().put("command",(1..100).joinToString("\n"){"printf QA_LINE_$it"}),true,"Deny")
}
