@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.interactions.InteractionsController
import app.codexremote.android.ui.interactions.InteractionDialogCompose
import org.json.JSONObject

class InteractionCancellationDeviceTest : InstrumentationTestCase() {
    fun testCancelWaitsForAcknowledgementAndCanRecoverInAllForms() {
        val fixtures = listOf(
            "item/tool/requestUserInput" to """{"questions":[{"id":"answer","question":"Choose an answer"}]}""",
            "mcpServer/elicitation/request" to """{"mode":"form","serverName":"QA","requestedSchema":{"type":"object","properties":{"value":{"type":"string"}}}}""",
            "mcpServer/elicitation/request" to """{"mode":"url","serverName":"QA","message":"Complete authorization","url":"https://example.com/qa"}"""
        )
        val replies = mutableListOf<JSONObject>()
        val controller = InteractionsController(onCancelReply = { _, reply, _ -> replies += reply })
        val activity = instrumentation.composeFixture { InteractionDialogCompose(controller) }
        try {
            for ((method, raw) in fixtures) {
                instrumentation.runOnMainSync { replies.clear(); controller.showInteraction("request", method, JSONObject(raw)) }
                instrumentation.clickUi(if (JSONObject(raw).optString("mode") == "url") "Close" else "Cancel")
                instrumentation.runOnMainSync {
                    assertEquals(1, replies.size); assertTrue(controller.uiState.value!!.isBusy)
                    if (method == "item/tool/requestUserInput") assertEquals(0, replies.single().getJSONObject("answers").length())
                    else assertEquals("cancel", replies.single().getString("action"))
                    controller.cancel(); assertEquals(1, replies.size)
                    controller.rejected("QA response rejected; retry")
                }
                instrumentation.awaitUiText("QA response rejected; retry")
                instrumentation.clickUi(if (JSONObject(raw).optString("mode") == "url") "Close" else "Cancel")
                instrumentation.runOnMainSync { assertEquals(2, replies.size); assertNotNull(controller.uiState.value); controller.dismissInteraction("request") }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testImmediateTransportFailureDoesNotLeaveButtonsLocked() {
        val controller = InteractionsController(onSubmitReply = { _, _, cb -> cb(false) })
        instrumentation.runOnMainSync {
            controller.showInteraction("request", "mcpServer/elicitation/request", JSONObject("""{"mode":"url","url":"https://example.com/qa"}"""))
            controller.submit(); assertFalse(controller.uiState.value!!.isBusy); assertFalse(controller.uiState.value!!.isSubmitting)
        }
    }
}
